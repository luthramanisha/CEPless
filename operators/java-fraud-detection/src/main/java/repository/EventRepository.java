package repository;

public interface EventRepository {
    void listen(String addr);
    void send(String addr, String item);
}
