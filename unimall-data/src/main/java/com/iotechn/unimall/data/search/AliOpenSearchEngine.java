package com.iotechn.unimall.data.search;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.opensearch.DocumentClient;
import com.aliyun.opensearch.OpenSearchClient;
import com.aliyun.opensearch.SearcherClient;
import com.aliyun.opensearch.sdk.dependencies.com.google.common.collect.Lists;
import com.aliyun.opensearch.sdk.generated.OpenSearch;
import com.aliyun.opensearch.sdk.generated.commons.OpenSearchClientException;
import com.aliyun.opensearch.sdk.generated.commons.OpenSearchException;
import com.aliyun.opensearch.sdk.generated.commons.OpenSearchResult;
import com.aliyun.opensearch.sdk.generated.document.Command;
import com.aliyun.opensearch.sdk.generated.document.DocumentConstants;
import com.aliyun.opensearch.sdk.generated.search.*;
import com.aliyun.opensearch.sdk.generated.search.general.SearchResult;
import com.iotechn.unimall.core.util.ReflectUtil;
import com.iotechn.unimall.core.util.SHAUtil;
import com.iotechn.unimall.data.annotation.SearchField;
import com.iotechn.unimall.data.annotation.SearchTable;
import com.iotechn.unimall.data.enums.SearchEngineTokenizerType;
import com.iotechn.unimall.data.model.Page;
import com.iotechn.unimall.data.model.SearchEngineInitModel;
import com.iotechn.unimall.data.model.SearchWrapperModel;
import com.iotechn.unimall.data.properties.UnimallSearchEngineProperties;
import com.iotechn.unimall.data.search.exception.SearchEngineException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Description:
 * User: rize
 * Date: 2020/8/14
 * Time: 15:42
 */
public class AliOpenSearchEngine extends AbstractSearchEngine implements InitializingBean {

    private OkHttpClient okHttpClient = new OkHttpClient();

    // RESTAPI HEADER ??? Content-Type ????????????
    private static final String contentType = "application/json";

    /******** ???????????????????????????????????? *********/

    /**
     * Document: ??????PUSH??????
     * ??????DocumentClient????????????json??????doc??????????????????
     */
    private DocumentClient documentClient;

    /**
     * Searcher: ??????????????????
     * ??????SearcherClient???????????????OpenSearchClient????????????????????????
     */
    private SearcherClient searcherClient;

    private Map<Class, Map<String, Method>> fieldMethodMapCache = new HashMap<>();

    @Autowired
    private UnimallSearchEngineProperties unimallSearchEngineProperties;

    private static final String APPNAME = "unimall";

    private static final Logger logger = LoggerFactory.getLogger(AliOpenSearchEngine.class);


    @Override
    public void afterPropertiesSet() throws Exception {
        // ??????IoC?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        this.reloadProperties();
    }

    /**
     * ??????????????????????????????Unimall?????????OpenSearch?????????
     * @param initModel
     */
    @Override
    public void init(SearchEngineInitModel initModel) {
        // 1. ????????????????????????Class???Ali OpenSearch??????????????????????????????
        List<STable> sTables = super.parseSearchTable(initModel);
        // 2. ??????AliOpenSearch??????API?????????Ali??????Api??????????????????2020???8???14???16:00:15????????????????????????RestApi???????????????
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        Date now = new Date();
        String date = sdf.format(now);
        String nonce = System.nanoTime() + "";
        String verb = "GET";
        String url = "/v4/openapi/app-groups?type=enhanced&sortBy=1";
        String signature = this.getSignature(verb, date, nonce, url, null);
        // ????????????
        // MediaType mediaType = MediaType.parse(contentType);
        // RequestBody requestBody = RequestBody.create(mediaType, "{}");
        Request request = new Request
                .Builder()
                .url(this.unimallSearchEngineProperties.getOpenSearchHost() + url)
                .get()
                .addHeader("Content-MD5", "")
                .addHeader("Content-Type", contentType)
                .addHeader("Authorization", "OPENSEARCH " + this.unimallSearchEngineProperties.getOpenSearchAccessKeyId() + ":" + signature)
                .addHeader("X-Opensearch-Nonce", nonce)
                .addHeader("Date", date).build();
        String json;
        try {
            Response execute = okHttpClient.newCall(request).execute();
            json = execute.body().string();
            logger.info("[Ali OpenSearch] response:\n" + json);
        } catch (IOException e) {
            throw new SearchEngineException("????????????????????????");
        }
    }

    public static void main(String args[]) {
        SearchEngineInitModel initModel = new SearchEngineInitModel();
        initModel.setTables(new ArrayList<>());
        new AliOpenSearchEngine().init(initModel);
    }

    @Override
    public boolean isInit() {
        return false;
    }

    @Override
    public boolean initAble() {
        return false;
    }

    @Override
    public void reloadProperties() {
        String accessKeyId = unimallSearchEngineProperties.getOpenSearchAccessKeyId();
        String accessKeySecret = unimallSearchEngineProperties.getOpenSearchAccessKeySecret();
        String host = unimallSearchEngineProperties.getOpenSearchHost();
        if (!StringUtils.isEmpty(accessKeyId) && !StringUtils.isEmpty(accessKeySecret) && !StringUtils.isEmpty(host)) {
            OpenSearch openSearch = new OpenSearch(accessKeyId, accessKeySecret, host);
            OpenSearchClient serviceClient = new OpenSearchClient(openSearch);
            this.documentClient = new DocumentClient(serviceClient);
            this.searcherClient = new SearcherClient(serviceClient);
        }
    }

    @Override
    public void dataTransmission(Object object) {
        if (object == null) {
            throw new NullPointerException("OpenSearch ????????????????????????");
        }
        dataTransmissionList(Arrays.asList(object));
    }

    /**
     * @param list
     */
    @Override
    public void dataTransmissionList(List list) {
        this.checkClientExit();
        if (CollectionUtils.isEmpty(list)) {
            throw new SearchEngineException("Push??????????????????");
        }
        Class<?> clazz = list.get(0).getClass();
        STable sTable = super.parseSearchTableClass(clazz);
        int pageNo = 0;
        int pageSize = 100;
        do {
            // ??????
            List subList;
            if (list.size() > ((pageNo + 1) * pageSize)) {
                subList = list.subList(pageNo * pageSize, ((pageNo + 1) * pageSize));
            } else {
                subList = list.subList(pageNo * pageSize, list.size());
            }
            pageNo++;
            Object docsJsonArr = subList.stream().map(item -> {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put(DocumentConstants.DOC_KEY_CMD, Command.ADD.toString());
                jsonObject.put(DocumentConstants.DOC_KEY_FIELDS, super.getSearchTableJsonObject(item));
                return jsonObject;
            }).collect(Collectors.toList());
            String docsJson = JSONObject.toJSONString(docsJsonArr);
            try {
                //??????????????????
                OpenSearchResult osr = documentClient.push(docsJson, APPNAME, sTable.getTitle());
                //???????????????????????????????????????????????????2???????????????????????????????????????????????????????????????????????????????????????????????????
                //????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                if (osr.getResult().equalsIgnoreCase("true")) {
                    logger.info("[OpenSearch PushList] ?????? RequestId:" + osr.getTraceInfo().getRequestId());
                } else {
                    logger.error("[OpenSearch PushList] ?????? Info:" + osr.getTraceInfo());
                }
            } catch (Exception e) {
                logger.error("[OpenSearch PushList] ?????? ??? {} ??? docs: {}", pageNo + 1, docsJson);
                logger.error("[OpenSearch PushList] ??????", e);
            }
        } while (list.size() > pageNo * pageSize);
    }

    @Override
    public void deleteData(Object obj) {
        try {
            Class<?> clazz = obj.getClass();
            Method method = clazz.getMethod("getId");
            SearchTable searchTable = clazz.getAnnotation(SearchTable.class);
            if (searchTable == null) {
                throw new SearchEngineException("?????????pojo?????????SearchTable?????????");
            }
            Object id = method.invoke(obj);
            Map<String, Object> map = new HashMap<>();
            map.put("id", id);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put(DocumentConstants.DOC_KEY_CMD, Command.DELETE.toString());
            jsonObject.put(DocumentConstants.DOC_KEY_FIELDS, map);
            String docsJson = JSONObject.toJSONString(Arrays.asList(jsonObject));
            documentClient.push(docsJson, APPNAME, searchTable.value());
        } catch (NoSuchMethodException e) {
            throw new SearchEngineException("??????Pojo??????id ??? Getter???Setter");
        } catch (IllegalAccessException e) {
            throw new SearchEngineException("??????Pojo??????id ??? Getter???Setter");
        } catch (InvocationTargetException e) {
            throw new SearchEngineException("??????Pojo??????id ??? Getter???Setter");
        } catch (OpenSearchException e) {
            logger.error("[OpenSearch Delete] ??????", e);
            throw new SearchEngineException("[OpenSearch Delete] ??????");
        } catch (OpenSearchClientException e) {
            logger.error("[OpenSearch Delete] ??????", e);
            throw new SearchEngineException("[OpenSearch Delete] ??????");
        }
    }

    /**
     * ????????? OpenSearch??? ????????????(?????????)???????????????????????????????????????????????????
     * @param wrapperModel ????????????wrapper
     * @param clazz ???????????? ????????????SearchTable????????? Java Pojo'class
     * @param <T>
     * @return
     */
    @Override
    public <T> Page<T> search(SearchWrapperModel wrapperModel, Class<T> clazz) {
        this.checkClientExit();
        // ??????????????????????????????????????????????????????????????????
        Config config = new Config(Lists.newArrayList(APPNAME));
        config.setStart((wrapperModel.getPageNo() - 1) * wrapperModel.getPageSize());
        config.setHits(wrapperModel.getPageSize());
        List<String> documentFields = super.getDocumentFields(clazz);
        config.setFetchFields(documentFields);
        config.setSearchFormat(SearchFormat.JSON);
        // ??????????????????
        SearchParams searchParams = new SearchParams(config);
        List<SearchWrapperModel.Condition> conditions = wrapperModel.getConditions();
        String query = conditions.stream().map(item -> {
            switch (item.getType()) {
                case SearchWrapperModel.CONDITION_TYPE_ACCURATE:
                case SearchWrapperModel.CONDITION_TYPE_FUZZY:
                    SearchField searchField = super.getAnnotation(clazz, item.getField());
                    if (searchField.tokenizer() == SearchEngineTokenizerType.STAND) {
                        // 1. ???????????????
                        return "default:'" + item.getKeyword() + "'";
                    } else {
                        // 2. ??????????????????
                        return item.getField() + ":" + item.getKeyword();
                    }
                case SearchWrapperModel.CONDITION_TYPE_GTE:
                case SearchWrapperModel.CONDITION_TYPE_GT:
                    // FIXME ???????????????????????????
                    return item.getField() + ":[" + item.getKeyword().toString() + "," + "]";
                case SearchWrapperModel.CONDITION_TYPE_LTE:
                case SearchWrapperModel.CONDITION_TYPE_LT:
                    return item.getField() + ":[" + "," + item.getKeyword().toString() + "]";
                case SearchWrapperModel.CONDITION_TYPE_BETWEEN:
                    return item.getField() + ":[" + item.getKeyword() + "," + item.getKeyword2() + "]";
            }
            return item.getField() + ":" + item.getKeyword() ;
        }).collect(Collectors.joining(" AND "));
        searchParams.setQuery(query);
        // ????????????
        Sort sorter = new Sort();
        Boolean isAsc = wrapperModel.getIsAsc();
        String orderByField = wrapperModel.getOrderByField();
        sorter.addToSortFields(new SortField(orderByField, isAsc ? Order.INCREASE : Order.DECREASE));
        sorter.addToSortFields(new SortField("RANK", Order.INCREASE));
        searchParams.setSort(sorter);
        // ????????????????????????
        try {
            SearchResult searchResult = searcherClient.execute(searchParams);
            String resultStr = searchResult.getResult();
            JSONObject result = JSONObject.parseObject(resultStr).getJSONObject("result");
            if (!CollectionUtils.isEmpty(result.getJSONArray("errors"))) {
                logger.error("[????????? OpenSearch] ???????????? ??????:" + resultStr);
            }
            if (result != null) {
                JSONArray items = result.getJSONArray("items");
                Integer total = result.getInteger("total");
                if (total == null || total == 0) {
                    // ????????????
                    return new Page<T>(new ArrayList<>(), wrapperModel.getPageNo(), wrapperModel.getPageSize(), 0);
                }
                // ???????????? Field Setter ?????????

                Map<String, Method> fieldMethodMap;
                // ????????????JVM Cache?????????
                fieldMethodMap = fieldMethodMapCache.get(clazz);
                if (fieldMethodMap == null) {
                    synchronized (this) {
                        if (fieldMethodMap == null) {
                            fieldMethodMap = new HashMap<>();
                            // ?????????????????? | ???????????????????????????????????????
                            Field[] declaredFields = clazz.getDeclaredFields();
                            for (Field field : declaredFields) {
                                SearchField searchField = field.getDeclaredAnnotation(SearchField.class);
                                if (searchField != null) {
                                    String methodName = ReflectUtil.getMethodName(field.getName(), "set");
                                    Method method = clazz.getMethod(methodName, field.getType());
                                    fieldMethodMap.put(searchField.value(), method);
                                }
                            }
                        }
                    }
                }
                final Map<String, Method> finalFieldMethodMap = fieldMethodMap;
                // ????????????????????????
                List<T> list = items.stream().map(item -> {
                    try {
                        JSONObject json = (JSONObject) item;
                        T t = (T) clazz.getConstructor().newInstance();
                        Set<String> keys = json.keySet();
                        for (String key : keys) {
                            Method method = finalFieldMethodMap.get(key);
                            if (method != null) {
                                // ????????????????????? fields ??????????????? index_name???????????????unimall?????????
                                Class<?> parameterType = method.getParameterTypes()[0];
                                // ???????????? & string ????????????
                                if (parameterType == String.class) {
                                    method.invoke(t, json.getString(key));
                                } else if (parameterType == Integer.class) {
                                    method.invoke(t, json.getInteger(key));
                                } else if (parameterType == Long.class) {
                                    method.invoke(t, json.getLong(key));
                                } else if (parameterType == Boolean.class) {
                                    method.invoke(t, json.getBoolean(key));
                                } else if (parameterType == Date.class) {
                                    method.invoke(t, json.getDate(key));
                                } else if (parameterType == Float.class) {
                                    method.invoke(t, json.getFloat(key));
                                } else if (parameterType == Double.class) {
                                    method.invoke(t, json.getDouble(key));
                                } else {
                                    method.invoke(t, json.getObject(key, parameterType));
                                }
                            }

                        }
                        return t;
                    } catch (Exception e) {
                        logger.error("[OpenSearch ????????????] ??????", e);
                        throw new SearchEngineException("?????????????????????????????????pojo");
                    }
                }).collect(Collectors.toList());
                return new Page<T>(list, wrapperModel.getPageNo(), wrapperModel.getPageSize(), total);
            }
            return new Page<T>(new ArrayList<>(), wrapperModel.getPageNo(), wrapperModel.getPageSize(), 0);
        } catch (Exception e) {
            logger.error("[OpenSearch] ?????? ??????", e);
            throw new SearchEngineException("???????????????????????????");
        }
    }

    /**
     * ???????????????????????? & ????????????
     * https://help.aliyun.com/document_detail/54237.html?spm=a2c4g.11186623.6.687.38ce1463yt4TJp
     * Signature = base64(hmac-sha1(AccessKeySecret,
     *             VERB + "\n"
     *             + Content-Md5 + "\n"
     *             + Content-Type + "\n"
     *             + Date + "\n"
     *             + CanonicalizedOpenSearchHeaders
     *             + CanonicalizedResource))
     * @param verb Http??????method GET???POST
     * @param date ??????????????????????????? 2019-02-25T10:09:57Z
     * @param nonce ?????????
     * @param url ???????????????URL
     * @return
     */
    private String getSignature(String verb, String date, String nonce, String url, String body) {
        try {
            String beforeSha1 = verb + "\n"
                    + "\n"
                    + contentType + "\n"
                    + date + "\n"
                    + "x-opensearch-nonce:" + nonce + "\n"
                    + url;
            logger.info("request:\n" + beforeSha1);
            byte[] bytes = Base64.getEncoder().encode(SHAUtil.hmacSHA1Encrypt(beforeSha1, this.unimallSearchEngineProperties.getOpenSearchAccessKeySecret()));
            return new String(bytes, "utf-8");
        } catch (Exception e) {
            logger.error("[Ali OpenSearch] ???????????? ??????", e);
        }
        return null;
    }

    private void checkClientExit() {
        if (this.documentClient == null) {
            throw new SearchEngineException("?????????OpenSearch:AccessKeyId,AccessKeySecret,??????????????????");
        } else if (this.searcherClient == null) {
            throw new SearchEngineException("?????????OpenSearch:AccessKeyId,AccessKeySecret,??????????????????");
        }
    }

}
