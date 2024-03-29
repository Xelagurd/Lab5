package lab5;

import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.pattern.Patterns;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.japi.Pair;
import akka.util.ByteString;
import akka.util.Timeout;
import org.asynchttpclient.Response;
import scala.concurrent.Await;
import scala.concurrent.Future;
import org.asynchttpclient.*;

import static org.asynchttpclient.Dsl.asyncHttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Server {

    private static final String ACTOR_SYSTEM_NAME = "JSServerActorSystem";
    private static final String BASE_URI = "/";
    private static final String TEST_URL = "testURL";
    private static final String COUNT = "count";
    private static final String EMPTY_STRING = "";
    private static final String EMPTY_TEST_URL = "testURL is empty";
    private static final String EMPTY_COUNT = "count is empty";
    private static final int REQUEST_ACTOR_TIMEOUT = 5000;
    private static final String ERROR_404 = "404";
    private static final String HOST = "localhost";
    private static final int PORT = 8083;
    private static final int NO_ANSWER_MSG = -1;

    public static void main(String[] args) throws IOException {
        /*а. Инициализация http сервера в akka */
        ActorSystem system = ActorSystem.create(ACTOR_SYSTEM_NAME);
        final ActorMaterializer materializer = ActorMaterializer.create(system);
        ActorRef cacheActor = system.actorOf(Props.create(CacheActor.class));


        final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = Flow.of(HttpRequest.class).map(
                request -> {
                    if (request.getUri().path().equals(BASE_URI)) {
                        String testURL = request.getUri().query().get(TEST_URL).orElse(EMPTY_STRING);
                        String count = request.getUri().query().get(COUNT).orElse(EMPTY_STRING);
                        if (testURL.isEmpty()) {
                            return HttpResponse.create().withEntity(ByteString.fromString(EMPTY_TEST_URL));
                        } else if (count.isEmpty()) {
                            return HttpResponse.create().withEntity(ByteString.fromString(EMPTY_COUNT));
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

                                            return Patterns.ask(cacheActor,
                                                    new GetMessage(input.first(), input.second()),
                                                    REQUEST_ACTOR_TIMEOUT).then(answer -> {

                                                if ((int) answer != NO_ANSWER_MSG) {
                                                    System.out.println("Answer will get from cache");
                                                    return CompletableFuture.completedFuture((int) answer);
                                                }

                                                Sink<CompletionStage<Long>, CompletionStage<Integer>> fold = Sink.
                                                        fold(0, (accumulator, element) -> {
                                                            int responseTime = (int) (element.toCompletableFuture().get() + 0);
                                                            return accumulator + responseTime;
                                                        });

                                                return Source.from(Collections.singletonList(pair)).toMat(
                                                        /*C помощью метода create создаем Flow */
                                                        Flow.<Pair<HttpRequest, Integer>>create()
                                                                /*mapConcat размножаем сообщения до нужного количества копий */
                                                                .mapConcat(p -> Collections.nCopies(p.second(), p.first()))

                                                                .mapAsync(1, request2 -> {
                                                                    return CompletableFuture.supplyAsync(() ->
                                                                            System.currentTimeMillis()
                                                                    ).thenCompose(start -> CompletableFuture.supplyAsync(() -> {
                                                                        CompletionStage<Long> whenResponse = asyncHttpClient()
                                                                                .prepareGet(request2.getUri().toString())
                                                                                .execute()
                                                                                .toCompletableFuture()
                                                                                .thenCompose(answer2 ->
                                                                                        CompletableFuture.completedFuture(System.currentTimeMillis() - start));
                                                                        return whenResponse;
                                                                    }));
                                                                })
                                                                /*в данном случае fold — это аггрегатор который подсчитывает
                                                                сумму всех времен, создаем его с помощью Sink.fold() */
                                                                .toMat(fold, Keep.right()), Keep.right()).run(materializer);

                                            }).thenCompose(sum -> {
                                                Patterns.ask(cacheActor, new StoreMessage(testURL, countInteger, sum), REQUEST_ACTOR_TIMEOUT);
                                                Double middleValue = (double) sum / (double) countInteger;
                                                System.out.println("Middle response value is " + middleValue.toString() + " ms");
                                                return HttpResponse.create().withEntity(ByteString.fromString("middle response value is " + middleValue.toString() + " ms"));
                                            });
                                        });
                                CompletionStage<HttpResponse> result = source.via(flow).toMat(Sink.last(), Keep.right()).run(materializer);
                                return result.toCompletableFuture().get();
                            } catch (NumberFormatException e) {
                                return HttpResponse.create().withEntity(ByteString.fromString("count is not an integer"));
                            }
                        }
                    } else {
                        return HttpResponse.create().withEntity(ByteString.fromString(ERROR_404));
                    }
                });
        final CompletionStage<ServerBinding> serverBindingFuture =
                Http.get(system).bindAndHandle(routeFlow, ConnectHttp.toHost(HOST, PORT), materializer);

        System.out.println("Server online at http://localhost:8083/\nPress RETURN to stop...");
        System.in.read();

        serverBindingFuture
                .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
                .thenAccept(unbound -> system.terminate()); // and shutdown when done

    }
}


