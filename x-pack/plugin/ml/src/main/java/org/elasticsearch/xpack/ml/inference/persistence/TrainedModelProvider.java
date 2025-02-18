/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.inference.persistence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkAction;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.MultiSearchAction;
import org.elasticsearch.action.search.MultiSearchRequestBuilder;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.CheckedBiFunction;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xpack.core.action.util.ExpandedIdsMatcher;
import org.elasticsearch.xpack.core.action.util.PageParams;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelConfig;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelDefinition;
import org.elasticsearch.xpack.core.ml.inference.persistence.InferenceIndexConstants;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.core.ml.utils.ToXContentParams;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;
import static org.elasticsearch.xpack.core.ml.job.messages.Messages.INFERENCE_FAILED_TO_DESERIALIZE;

public class TrainedModelProvider {

    private static final Logger logger = LogManager.getLogger(TrainedModelProvider.class);
    private final Client client;
    private final NamedXContentRegistry xContentRegistry;
    private static final ToXContent.Params FOR_INTERNAL_STORAGE_PARAMS =
        new ToXContent.MapParams(Collections.singletonMap(ToXContentParams.FOR_INTERNAL_STORAGE, "true"));

    public TrainedModelProvider(Client client, NamedXContentRegistry xContentRegistry) {
        this.client = client;
        this.xContentRegistry = xContentRegistry;
    }

    public void storeTrainedModel(TrainedModelConfig trainedModelConfig, ActionListener<Boolean> listener) {

        if (trainedModelConfig.getDefinition() == null) {
            listener.onFailure(ExceptionsHelper.badRequestException("Unable to store [{}]. [{}] is required",
                trainedModelConfig.getModelId(),
                TrainedModelConfig.DEFINITION.getPreferredName()));
            return;
        }

        BulkRequest bulkRequest = client.prepareBulk(InferenceIndexConstants.LATEST_INDEX_NAME)
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            .add(createRequest(trainedModelConfig.getModelId(), trainedModelConfig))
            .add(createRequest(TrainedModelDefinition.docId(trainedModelConfig.getModelId()), trainedModelConfig.getDefinition()))
            .request();

        ActionListener<Boolean> wrappedListener = ActionListener.wrap(
            listener::onResponse,
            e -> {
                if (ExceptionsHelper.unwrapCause(e) instanceof VersionConflictEngineException) {
                    listener.onFailure(new ResourceAlreadyExistsException(
                        Messages.getMessage(Messages.INFERENCE_TRAINED_MODEL_EXISTS, trainedModelConfig.getModelId())));
                } else {
                    listener.onFailure(
                        new ElasticsearchStatusException(Messages.INFERENCE_FAILED_TO_STORE_MODEL,
                            RestStatus.INTERNAL_SERVER_ERROR,
                            e,
                            trainedModelConfig.getModelId()));
                }
            }
        );

        ActionListener<BulkResponse> bulkResponseActionListener = ActionListener.wrap(
            r -> {
                assert r.getItems().length == 2;
                if (r.getItems()[0].isFailed()) {
                    logger.error(new ParameterizedMessage(
                        "[{}] failed to store trained model config for inference",
                        trainedModelConfig.getModelId()),
                        r.getItems()[0].getFailure().getCause());
                    wrappedListener.onFailure(r.getItems()[0].getFailure().getCause());
                    return;
                }
                if (r.getItems()[1].isFailed()) {
                    logger.error(new ParameterizedMessage(
                        "[{}] failed to store trained model definition for inference",
                        trainedModelConfig.getModelId()),
                        r.getItems()[1].getFailure().getCause());
                    wrappedListener.onFailure(r.getItems()[1].getFailure().getCause());
                    return;
                }
                wrappedListener.onResponse(true);
            },
            wrappedListener::onFailure
        );

        executeAsyncWithOrigin(client, ML_ORIGIN, BulkAction.INSTANCE, bulkRequest, bulkResponseActionListener);
    }

    public void getTrainedModel(final String modelId, final boolean includeDefinition, final ActionListener<TrainedModelConfig> listener) {

        QueryBuilder queryBuilder = QueryBuilders.constantScoreQuery(QueryBuilders
            .idsQuery()
            .addIds(modelId));
        MultiSearchRequestBuilder multiSearchRequestBuilder = client.prepareMultiSearch()
            .add(client.prepareSearch(InferenceIndexConstants.INDEX_PATTERN)
                .setQuery(queryBuilder)
                // use sort to get the last
                .addSort("_index", SortOrder.DESC)
                .setSize(1)
                .request());

        if (includeDefinition) {
            multiSearchRequestBuilder.add(client.prepareSearch(InferenceIndexConstants.INDEX_PATTERN)
                .setQuery(QueryBuilders.constantScoreQuery(QueryBuilders
                    .idsQuery()
                    .addIds(TrainedModelDefinition.docId(modelId))))
                // use sort to get the last
                .addSort("_index", SortOrder.DESC)
                .setSize(1)
                .request());
        }

        ActionListener<MultiSearchResponse> multiSearchResponseActionListener = ActionListener.wrap(
            multiSearchResponse -> {
                TrainedModelConfig.Builder builder;
                TrainedModelDefinition definition;
                try {
                    builder = handleSearchItem(multiSearchResponse.getResponses()[0], modelId, this::parseInferenceDocLenientlyFromSource);
                } catch (ResourceNotFoundException ex) {
                    listener.onFailure(new ResourceNotFoundException(
                        Messages.getMessage(Messages.INFERENCE_NOT_FOUND, modelId)));
                    return;
                } catch (Exception ex) {
                    listener.onFailure(ex);
                    return;
                }

                if (includeDefinition) {
                    try {
                        definition = handleSearchItem(multiSearchResponse.getResponses()[1],
                            modelId,
                            this::parseModelDefinitionDocLenientlyFromSource);
                        builder.setDefinition(definition);
                    } catch (ResourceNotFoundException ex) {
                        listener.onFailure(new ResourceNotFoundException(
                            Messages.getMessage(Messages.MODEL_DEFINITION_NOT_FOUND, modelId)));
                        return;
                    } catch (Exception ex) {
                        listener.onFailure(ex);
                        return;
                    }
                }
                listener.onResponse(builder.build());
            },
            listener::onFailure
        );

        executeAsyncWithOrigin(client,
            ML_ORIGIN,
            MultiSearchAction.INSTANCE,
            multiSearchRequestBuilder.request(),
            multiSearchResponseActionListener);
    }

    /**
     * Gets all the provided trained config model objects
     *
     * NOTE:
     * This does no expansion on the ids.
     * It assumes that there are fewer than 10k.
     */
    public void getTrainedModels(Set<String> modelIds, boolean allowNoResources, final ActionListener<List<TrainedModelConfig>> listener) {
        QueryBuilder queryBuilder = QueryBuilders.constantScoreQuery(QueryBuilders.idsQuery().addIds(modelIds.toArray(new String[0])));

        SearchRequest searchRequest = client.prepareSearch(InferenceIndexConstants.INDEX_PATTERN)
            .addSort(TrainedModelConfig.MODEL_ID.getPreferredName(), SortOrder.ASC)
            .addSort("_index", SortOrder.DESC)
            .setQuery(queryBuilder)
            .request();

        ActionListener<SearchResponse> configSearchHandler = ActionListener.wrap(
            searchResponse -> {
                Set<String> observedIds = new HashSet<>(searchResponse.getHits().getHits().length, 1.0f);
                List<TrainedModelConfig> configs = new ArrayList<>(searchResponse.getHits().getHits().length);
                for(SearchHit searchHit : searchResponse.getHits().getHits()) {
                    try {
                        if (observedIds.contains(searchHit.getId()) == false) {
                            configs.add(
                                parseInferenceDocLenientlyFromSource(searchHit.getSourceRef(), searchHit.getId()).build()
                            );
                            observedIds.add(searchHit.getId());
                        }
                    } catch (IOException ex) {
                        listener.onFailure(
                            ExceptionsHelper.serverError(INFERENCE_FAILED_TO_DESERIALIZE, ex, searchHit.getId()));
                        return;
                    }
                }
                // We previously expanded the IDs.
                // If the config has gone missing between then and now we should throw if allowNoResources is false
                // Otherwise, treat it as if it was never expanded to begin with.
                Set<String> missingConfigs = Sets.difference(modelIds, observedIds);
                if (missingConfigs.isEmpty() == false && allowNoResources == false) {
                    listener.onFailure(new ResourceNotFoundException(Messages.INFERENCE_NOT_FOUND_MULTIPLE, missingConfigs));
                    return;
                }
                listener.onResponse(configs);
            },
            listener::onFailure
        );

        executeAsyncWithOrigin(client, ML_ORIGIN, SearchAction.INSTANCE, searchRequest, configSearchHandler);
    }

    public void deleteTrainedModel(String modelId, ActionListener<Boolean> listener) {
        DeleteByQueryRequest request = new DeleteByQueryRequest().setAbortOnVersionConflict(false);

        request.indices(InferenceIndexConstants.INDEX_PATTERN);
        QueryBuilder query = QueryBuilders.termQuery(TrainedModelConfig.MODEL_ID.getPreferredName(), modelId);
        request.setQuery(query);
        request.setRefresh(true);

        executeAsyncWithOrigin(client, ML_ORIGIN, DeleteByQueryAction.INSTANCE, request, ActionListener.wrap(deleteResponse -> {
            if (deleteResponse.getDeleted() == 0) {
                listener.onFailure(new ResourceNotFoundException(
                    Messages.getMessage(Messages.INFERENCE_NOT_FOUND, modelId)));
                return;
            }
            listener.onResponse(true);
        }, e -> {
            if (e.getClass() == IndexNotFoundException.class) {
                listener.onFailure(new ResourceNotFoundException(
                    Messages.getMessage(Messages.INFERENCE_NOT_FOUND, modelId)));
            } else {
                listener.onFailure(e);
            }
        }));
    }

    public void expandIds(String idExpression,
                          boolean allowNoResources,
                          @Nullable PageParams pageParams,
                          ActionListener<Tuple<Long, Set<String>>> idsListener) {
        String[] tokens = Strings.tokenizeToStringArray(idExpression, ",");
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
            .sort(SortBuilders.fieldSort(TrainedModelConfig.MODEL_ID.getPreferredName())
                // If there are no resources, there might be no mapping for the id field.
                // This makes sure we don't get an error if that happens.
                .unmappedType("long"))
            .query(buildQueryIdExpressionQuery(tokens, TrainedModelConfig.MODEL_ID.getPreferredName()));
        if (pageParams != null) {
            sourceBuilder.from(pageParams.getFrom()).size(pageParams.getSize());
        }
        sourceBuilder.trackTotalHits(true)
            // we only care about the item id's
            .fetchSource(TrainedModelConfig.MODEL_ID.getPreferredName(), null);

        IndicesOptions indicesOptions = SearchRequest.DEFAULT_INDICES_OPTIONS;
        SearchRequest searchRequest = new SearchRequest(InferenceIndexConstants.INDEX_PATTERN)
            .indicesOptions(IndicesOptions.fromOptions(true,
                indicesOptions.allowNoIndices(),
                indicesOptions.expandWildcardsOpen(),
                indicesOptions.expandWildcardsClosed(),
                indicesOptions))
            .source(sourceBuilder);

        executeAsyncWithOrigin(client.threadPool().getThreadContext(),
            ML_ORIGIN,
            searchRequest,
            ActionListener.<SearchResponse>wrap(
                response -> {
                    Set<String> foundResourceIds = new LinkedHashSet<>();
                    long totalHitCount = response.getHits().getTotalHits().value;
                    for (SearchHit hit : response.getHits().getHits()) {
                        Map<String, Object> docSource = hit.getSourceAsMap();
                        if (docSource == null) {
                            continue;
                        }
                        Object idValue = docSource.get(TrainedModelConfig.MODEL_ID.getPreferredName());
                        if (idValue instanceof String) {
                            foundResourceIds.add(idValue.toString());
                        }
                    }
                    ExpandedIdsMatcher requiredMatches = new ExpandedIdsMatcher(tokens, allowNoResources);
                    requiredMatches.filterMatchedIds(foundResourceIds);
                    if (requiredMatches.hasUnmatchedIds()) {
                        idsListener.onFailure(ExceptionsHelper.missingTrainedModel(requiredMatches.unmatchedIdsString()));
                    } else {
                        idsListener.onResponse(Tuple.tuple(totalHitCount, foundResourceIds));
                    }
                },
                idsListener::onFailure
            ),
            client::search);

    }

    private QueryBuilder buildQueryIdExpressionQuery(String[] tokens, String resourceIdField) {
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
            .filter(QueryBuilders.termQuery(InferenceIndexConstants.DOC_TYPE.getPreferredName(), TrainedModelConfig.NAME));

        if (Strings.isAllOrWildcard(tokens)) {
            return boolQuery;
        }
        // If the resourceId is not _all or *, we should see if it is a comma delimited string with wild-cards
        // e.g. id1,id2*,id3
        BoolQueryBuilder shouldQueries = new BoolQueryBuilder();
        List<String> terms = new ArrayList<>();
        for (String token : tokens) {
            if (Regex.isSimpleMatchPattern(token)) {
                shouldQueries.should(QueryBuilders.wildcardQuery(resourceIdField, token));
            } else {
                terms.add(token);
            }
        }
        if (terms.isEmpty() == false) {
            shouldQueries.should(QueryBuilders.termsQuery(resourceIdField, terms));
        }

        if (shouldQueries.should().isEmpty() == false) {
            boolQuery.filter(shouldQueries);
        }
        return boolQuery;
    }

    private static <T> T handleSearchItem(MultiSearchResponse.Item item,
                                          String resourceId,
                                          CheckedBiFunction<BytesReference, String, T, Exception> parseLeniently) throws Exception {
        if (item.isFailure()) {
            throw item.getFailure();
        }
        if (item.getResponse().getHits().getHits().length == 0) {
            throw new ResourceNotFoundException(resourceId);
        }
        return parseLeniently.apply(item.getResponse().getHits().getHits()[0].getSourceRef(), resourceId);
    }

    private TrainedModelConfig.Builder parseInferenceDocLenientlyFromSource(BytesReference source, String modelId) throws IOException {
        try (InputStream stream = source.streamInput();
             XContentParser parser = XContentFactory.xContent(XContentType.JSON)
                 .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, stream)) {
            return TrainedModelConfig.fromXContent(parser, true);
        } catch (IOException e) {
            logger.error(new ParameterizedMessage("[{}] failed to parse model", modelId), e);
            throw e;
        }
    }

    private TrainedModelDefinition parseModelDefinitionDocLenientlyFromSource(BytesReference source, String modelId) throws IOException {
        try (InputStream stream = source.streamInput();
             XContentParser parser = XContentFactory.xContent(XContentType.JSON)
                 .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, stream)) {
            return TrainedModelDefinition.fromXContent(parser, true).build();
        } catch (IOException e) {
            logger.error(new ParameterizedMessage("[{}] failed to parse model definition", modelId), e);
            throw e;
        }
    }

    private IndexRequest createRequest(String docId, ToXContentObject body) {
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            XContentBuilder source = body.toXContent(builder, FOR_INTERNAL_STORAGE_PARAMS);

            return new IndexRequest()
                .opType(DocWriteRequest.OpType.CREATE)
                .id(docId)
                .source(source);
        } catch (IOException ex) {
            // This should never happen. If we were able to deserialize the object (from Native or REST) and then fail to serialize it again
            // that is not the users fault. We did something wrong and should throw.
            throw ExceptionsHelper.serverError(
                new ParameterizedMessage("Unexpected serialization exception for [{}]", docId).getFormattedMessage(),
                ex);
        }
    }
}
