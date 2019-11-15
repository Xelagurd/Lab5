package lab5;

import akka.actor.AbstractActor;
import akka.japi.pf.ReceiveBuilder;

import java.util.HashMap;
import java.util.Map;

public class CacheActor extends AbstractActor {
    /*б. В приложении будем использовать следующие акторы :
    - актор который хранит результаты тестов.
   Обрабатывает следующие сообщения :
   cообщение с результатом одного теста -> кладет его в локальное хранилище.
   Сообщение с запросом результата теста → отвечает сообщением с  результатом всех тестов для заданного  packageId */
    private Map<String, String> storage = new HashMap<>();

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(StoreMessage.class, m -> {
                    if (!storage.containsKey(m.getSite() + "|" + m.getRequestCount())) {
                        storage.put(m.getSite() + "|" + m.getRequestCount(), m.getResult());
                    }
                    System.out.println("Message for site: " + m.getSite() + " with count " + m.getRequestCount() + " received");
                })
                .match(GetMessage.class, req -> sender().tell(
                        new StoreMessage(req.getSite(), req.getRequestCount(), storage.get(req.getSite() + "|" + req.getRequestCount())), self())
                ).build();
    }
}
