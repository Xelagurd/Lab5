package lab5;

public class StoreMessage {
    private String site;
    private int requestCount;
    private String result;

    public StoreMessage(String site, int requestCount,String result) {
        this.site = site;
        this.requestCount = requestCount;
        this.result = result;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public String getSite() {
        return site;
    }

    public String getResult() {
        return result;
    }
}

