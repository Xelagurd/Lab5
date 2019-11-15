package lab5;

import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.Pair;
import akka.pattern.Patterns;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;

public class Server {

    public static void main(String[] args) throws IOException {
        /*а. Инициализация http сервера в akka */
        ActorSystem system = ActorSystem.create("JSServerActorSystem");
        final Http http = Http.get(system);
        final ActorMaterializer materializer = ActorMaterializer.create(system);
        ActorRef cacheActor = system.actorOf(Props.create(CacheActor.class));
        CompletionStage<ServerBinding> serverBindingFuture =
                Http.get(system).bindAndHandleSync(
                        request -> {
                            if (request.getUri().path().equals("/")) {
                                String testURL = request.getUri().query().get("testURL").orElse("");
                                String count = request.getUri().query().get("count").orElse("");
                                if (testURL.isEmpty()) {
                                    return HttpResponse.create().withEntity(ByteString.fromString("testURL is empty"));
                                } else if (count.isEmpty()) {
                                    return HttpResponse.create().withEntity(ByteString.fromString("count is empty"));
                                } else {
                                    try {
                                        Integer countInteger = Integer.parseInt(count);
                                        System.out.println("testURL = " + testURL);
                                        System.out.println("count = " + countInteger);
                                        Pair<String, Integer> input = new Pair<>(testURL, countInteger);

                                        Source<Pair<String, Integer>, NotUsed> source = Source.from(Collections.singletonList(input));
                                        /*б. Общая логика требуемого flow*/
                                        Flow<Pair<String, Integer>, HttpResponse, NotUsed> flow = Flow.<Pair<String, Integer>>create()
                                                /*map в Pair<HttpRequest этот запрос создан из query параметра, Integer количество запросов> */
                                                .map(pair -> new Pair<>(HttpRequest.create().withUri(pair.first()), pair.second()))
                                                /*mapAsync, создаем на лету flow из данных запроса, выполняем его и возвращаем*/
                                                .mapAsync(1, pair -> {
                                                    Future<Object> result = Patterns.ask(cacheActor,
                                                            new GetMessage(testURL, countInteger), 5000);

                                                    int answer = (int) Await.result(result, Duration.create(10, TimeUnit.SECONDS));
                                                    if (answer != -1) {
                                                        System.out.println("Answer from cache: ");
                                                        return CompletableFuture.completedFuture(answer);
                                                    }

                                                    Sink<Long, CompletionStage<Integer>> fold = Sink.
                                                            fold(0, (accumulator, element) -> {
                                                                int responseTime = (int) (element + 0);
                                                                return accumulator + responseTime;
                                                            });

                                                    return Source.from(Collections.singletonList(pair)).toMat(
                                                            /*C помощью метода create создаем Flow */
                                                            Flow.<Pair<HttpRequest, Integer>>create()
                                                                    /*mapConcat размножаем сообщения до нужного количества копий */
                                                                    .mapConcat(p -> Collections.nCopies(p.second(), p.first()))

                                                                    .mapAsync(1, request2 -> {
                                                                        CompletableFuture<Long> future = CompletableFuture.supplyAsync(() ->
                                                                                System.currentTimeMillis()
                                                                        ).thenCompose(s -> CompletableFuture.supplyAsync(() -> {
                                                                            ListenableFuture<Response> whenResponse = asyncHttpClient().prepareGet(request2.getUri().toString()).execute();
                                                                            try {
                                                                                Response response = whenResponse.get();
                                                                            } catch (InterruptedException | ExecutionException e) {
                                                                                e.printStackTrace();
                                                                            }
                                                                            return System.currentTimeMillis() - s;
                                                                        }));
                                                                        return future;
                                                                    })
                                                                    /*в данном случае fold — это аггрегатор который подсчитывает
                                                                    сумму всех времен, создаем его с помощью Sink.fold() */
                                                                    .toMat(fold, Keep.right()), Keep.right()).run(materializer);

                                                }).map(sum -> {
                                                    Patterns.ask(cacheActor, new StoreMessage(testURL, countInteger, sum.toString()), 5000);
                                                    Double middleValue = (double) sum / (double) countInteger;
                                                    return HttpResponse.create().withEntity(ByteString.fromString("middle response value is " + middleValue.toString() + " ms"));
                                                });

                                        CompletionStage<HttpResponse> result = source.via(flow).toMat(Sink.last(), Keep.right()).run(materializer);
                                        return result.toCompletableFuture().get();
                                    } catch (NumberFormatException e) {
                                        return HttpResponse.create().withEntity(ByteString.fromString("count is not an integer"));
                                    }
                                }
                            } else {
                                return HttpResponse.create().withEntity(ByteString.fromString("404"));
                            }
                        }, ConnectHttp.toHost("localhost", 8083), materializer);

        System.out.println("Server online at http://localhost:8083/\nPress RETURN to stop...");
        System.in.read();

        serverBindingFuture
                .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
                .thenAccept(unbound -> system.terminate()); // and shutdown when done

    }
}


