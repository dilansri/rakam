package org.rakam.aws;

import io.airlift.http.client.HttpClient;
import io.airlift.http.client.JsonBodyGenerator;
import io.airlift.http.client.Request;
import io.airlift.http.client.StringResponseHandler;
import io.airlift.json.JsonCodec;
import org.rakam.plugin.CollectionStreamQuery;
import org.rakam.plugin.EventStream;
import org.rakam.plugin.StreamResponse;
import org.rakam.report.PrestoConfig;

import javax.inject.Inject;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.List;

import static com.google.common.net.MediaType.JSON_UTF_8;
import static io.airlift.http.client.Request.Builder.*;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;

public class KinesisEventStream implements EventStream {

    private final HttpClient httpClient;
    private final int streamingPort;
    private final URI prestoAddress;
    private final JsonCodec<StreamQuery> queryCodec;

    @Inject
    public KinesisEventStream(@ForStreamer HttpClient httpClient, AWSKinesisModule.PrestoStreamConfig config, PrestoConfig prestoConfig) {
        this.httpClient = httpClient;
        this.streamingPort = config.getPort();
        this.prestoAddress = prestoConfig.getAddress();
        this.queryCodec = JsonCodec.jsonCodec(StreamQuery.class);
    }

    @Override
    public EventStreamer subscribe(String project, List<CollectionStreamQuery> collections, List<String> columns, StreamResponse response) {

        StreamQuery query = new StreamQuery(project, collections);

        URI uri = UriBuilder.fromUri(prestoAddress)
                .port(streamingPort)
                .path("connector/streamer/create-stream")
                .build();

        Request request = preparePost()
                .setUri(uri)
                .setHeader(CONTENT_TYPE, JSON_UTF_8.toString())
                .setBodyGenerator(JsonBodyGenerator.jsonBodyGenerator(queryCodec, query))
                .build();

        String ticket = httpClient.execute(request, StringResponseHandler.createStringResponseHandler()).getBody();

        return new EventStreamer() {
            @Override
            public synchronized void sync() {
                try {
                    URI uri = UriBuilder.fromUri(prestoAddress)
                            .port(streamingPort)
                            .path("connector/streamer")
                            .queryParam("ticket", ticket)
                            .build();

                    Request request = prepareGet().setUri(uri).build();
                    String data = httpClient.execute(request, StringResponseHandler.createStringResponseHandler()).getBody();

                    response.send("data", data);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void shutdown() {
                URI uri = UriBuilder.fromUri(prestoAddress)
                        .port(streamingPort)
                        .path("connector/streamer")
                        .queryParam("ticket", ticket)
                        .build();

                Request request = prepareDelete().setUri(uri).build();
                httpClient.execute(request, StringResponseHandler.createStringResponseHandler());
            }
        };
    }
}
