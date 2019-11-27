package lab5;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.japi.pf.ReceiveBuilder;

import java.util.HashMap;
import java.util.Map;

public class CacheActor extends AbstractActor {
    private static final int NO_ANSWER_MSG = -1;
    private Map<String, String> storage = new HashMap<>();

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(StoreMessage.class, m -> {
                    if (!storage.containsKey(m.getSite() + m.getRequestCount())) {
                        storage.put(m.getSite() + m.getRequestCount(), m.getResult());
                        System.out.println("Info for site: " + m.getSite() + " with count " + m.getRequestCount() + " added");
                    }
                })
                .match(GetMessage.class, req -> {
                    System.out.println("Message for site: " + req.getSite() + " with count " + req.getRequestCount() + " received");
                    if (storage.containsKey(req.getSite() + req.getRequestCount())) {
                        sender().tell(new Integer(storage.get(req.getSite() + req.getRequestCount())), ActorRef.noSender());
                    } else {
                        sender().tell(NO_ANSWER_MSG, ActorRef.noSender());
                    }
                }).build();
    }
}
