package lab5;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import akka.util.ByteString;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

public class Server {
    public static void main(String[] args) throws IOException {
        /*а. Инициализация http сервера в akka */
        ActorSystem system = ActorSystem.create("routes");
        final Http http = Http.get(system);
        final ActorMaterializer materializer = ActorMaterializer.create(system);

        final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = request -> {
            if (request.getUri().path().equals("/")) {
                String testURL = request.getUri().query().get("testURL").orElse("");
                String count = request.getUri().query().get("count").orElse("");
                if (testURL.isEmpty()) {
                    return HttpResponse.create().withEntity(ByteString.fromString("testURL is empty"));
                } else if (count.isEmpty()) {
                    return HttpResponse.create().withEntity(ByteString.fromString("count is empty"));
                } else {
                    try {
                    } catch (NumberFormatException e) {
                        return HttpResponse.create().withEntity(ByteString.fromString("count is not an integer"));
                    }
                }
            } else {
                return HttpResponse.create().withEntity(ByteString.fromString("404"));
            }
        };

        final CompletionStage<ServerBinding> binding = Http.get(system).bindAndHandle(
                routeFlow,
                ConnectHttp.toHost("localhost", 8080),
                materializer
        );

        System.out.println("Server online at http://localhost:8080/\nPress RETURN to stop...");
        System.in.read();
        binding
                .thenCompose(ServerBinding::unbind)
                .thenAccept(unbound -> system.terminate());
    }
}
