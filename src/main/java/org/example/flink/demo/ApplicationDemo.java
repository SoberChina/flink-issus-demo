package org.example.flink.demo;


import com.alibaba.fastjson.JSON;
import org.apache.commons.compress.utils.Lists;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.ParallelSourceFunction;
import org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchSinkFunction;
import org.apache.flink.streaming.connectors.elasticsearch.RequestIndexer;
import org.apache.flink.streaming.connectors.elasticsearch.util.IgnoringFailureHandler;
import org.apache.flink.streaming.connectors.elasticsearch7.ElasticsearchSink;
import org.apache.http.HttpHost;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.reactor.IOReactorException;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author liweigao
 * @date 2021/9/24 下午3:03
 */

public class ApplicationDemo {

    final static Logger logger = LoggerFactory.getLogger(ApplicationDemo.class);

    public static void main(String[] args) throws Exception {

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        //        StreamExecutionEnvironment env = new StreamExecutionEnvironment();

        DataStream<EventObject> dataStream = env.addSource(
                new ParallelSourceFunction<EventObject>() {
                    @Override
                    public void run(SourceContext<EventObject> sourceContext) throws Exception {


                        ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();


                        int index = 0;
                        while (true) {
                            Thread.sleep(1000);
                            //初始化数据 模拟 10%的迟到数据
                            Long timestamp = System.currentTimeMillis();
                            int number;
                            if ((number = threadLocalRandom.nextInt(100) + 1) > 90) {
                                timestamp -= (threadLocalRandom.nextInt(10000) + 1000);
                            }

                            EventObject eventObject = new EventObject();
                            eventObject.setTimestamp(timestamp);
                            eventObject.setContext("我的随机数为" + number);
                            eventObject.setRandom(number);
                            //随机设置类型 主要为了key by
                            index++;
                            if (index >= Type.values().length) {
                                index = 0;
                            }
                            eventObject.setType(Type.values()[index]);
                            logger.info("输入数据" + eventObject.toString());
                            sourceContext.collectWithTimestamp(eventObject, timestamp);
                        }
                    }

                    @Override
                    public void cancel() {
                        logger.info("this was canceled !!!!");
                    }
                }).setParallelism(1).name("random-stream").uid("random-stream");

        DataStream<EventObject> mapDataStream = dataStream.map(new MapFunction<EventObject, EventObject>() {
            @Override
            public EventObject map(EventObject eventObject) throws Exception {
                eventObject.setRandom(eventObject.getRandom() + 1);
                return eventObject;
            }
        }).name("map-data-stream").uid("map-data-stream");


        List<HttpHost> finalHostList = Lists.newArrayList();
        finalHostList.add(new HttpHost("localhost", 9200));
        ElasticsearchSink.Builder<EventObject> eventObjectBuilder =
                new ElasticsearchSink.Builder<EventObject>(finalHostList,
                        new ElasticsearchSinkFunction<EventObject>() {
                            @Override
                            public void process(EventObject element, RuntimeContext ctx, RequestIndexer indexer) {

                                IndexRequest indexRequest = new IndexRequest();
                                indexRequest.index("test-flink-job").id(String.valueOf(element.getTimestamp()))
                                        .source(JSON.parseObject(JSON.toJSONString(element))).opType(DocWriteRequest.OpType.INDEX);
                                indexer.add(indexRequest);
                            }
                        });

        eventObjectBuilder.setBulkFlushBackoff(true);
        eventObjectBuilder.setBulkFlushBackoffDelay(1000L);
        eventObjectBuilder.setFailureHandler(new IgnoringFailureHandler());
        eventObjectBuilder.setBulkFlushBackoffRetries(2);
        eventObjectBuilder.setBulkFlushMaxSizeMb(2);
        eventObjectBuilder.setBulkFlushInterval(2000L);
        //        eventObjectBuilder.setRestClientFactory(new RestClientFactory() {
        //            @Override
        //            public void configureRestClientBuilder(RestClientBuilder restClientBuilder) {
        //                restClientBuilder.setHttpClientConfigCallback(new RestClientBuilder
        //                .HttpClientConfigCallback() {
        //                    @Override
        //                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder
        //                    httpClientBuilder) {
        //                        IOReactorConfig ioReactorConfig = IOReactorConfig.custom().setSoKeepAlive(true)
        //                                .setIoThreadCount(1).build();
        //                        httpClientBuilder.setDefaultIOReactorConfig(ioReactorConfig);
        //                        httpClientBuilder.setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy());
        //                        try {
        //                            PoolingNHttpClientConnectionManager connectionManager =
        //                                    new PoolingNHttpClientConnectionManager(new DefaultConnectingIOReactor
        //                                    (ioReactorConfig));
        //                            connectionManager.setMaxTotal(10);
        //                            connectionManager.setDefaultMaxPerRoute(10);
        //                            httpClientBuilder.setConnectionManager(connectionManager);
        //                        } catch (IOReactorException e) {
        //                            logger.error(e.getMessage(), e);
        //                        }
        //
        ////                        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        ////                        credentialsProvider.setCredentials(AuthScope.ANY,
        ////                                new UsernamePasswordCredentials("",
        ////                                       ""));
        ////                        httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
        //                        return httpClientBuilder;
        //                    }
        //                });
        //            }
        //        });

        eventObjectBuilder.setRestClientFactory(restClientBuilder -> {
            restClientBuilder.setHttpClientConfigCallback(httpClientBuilder -> {
                IOReactorConfig ioReactorConfig = IOReactorConfig.custom().setSoKeepAlive(true)
                        .setIoThreadCount(1).build();
                httpClientBuilder.setDefaultIOReactorConfig(ioReactorConfig);
                httpClientBuilder.setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy());
                try {
                    PoolingNHttpClientConnectionManager connectionManager =
                            new PoolingNHttpClientConnectionManager(new DefaultConnectingIOReactor(ioReactorConfig));
                    connectionManager.setMaxTotal(10);
                    connectionManager.setDefaultMaxPerRoute(10);
                    httpClientBuilder.setConnectionManager(connectionManager);
                } catch (IOReactorException e) {
                    logger.error(e.getMessage(), e);
                }

                //                        final CredentialsProvider credentialsProvider = new
                //                        BasicCredentialsProvider();
                //                        credentialsProvider.setCredentials(AuthScope.ANY,
                //                                new UsernamePasswordCredentials("",
                //                                       ""));
                //                        httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                return httpClientBuilder;
            });
        });

        mapDataStream.addSink(eventObjectBuilder.build());
        env.execute("my-demo-job");
    }


    private static class EventObject {
        private Long timestamp;

        private String context;

        private Integer random;

        private Type type;

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }

        public Integer getRandom() {
            return random;
        }

        public void setRandom(Integer random) {
            this.random = random;
        }

        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return "EventObject{" +
                    "timestamp=" + timestamp +
                    ", context='" + context + '\'' +
                    ", random=" + random +
                    ", type=" + type +
                    '}';
        }
    }

}

enum Type {
    A, B, C
}
