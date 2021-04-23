
import manager.EventManager;
import operator.Operator;
import operator.OperatorProcessingInterface;

public class Main {

    public static void main(String[] args) throws Exception {
        String addrIn = System.getenv("ADDR_IN");
        String addrOut = System.getenv("ADDR_OUT");
        String dbType = System.getenv("DB_TYPE");
        String dbHost = System.getenv("DB_HOST");

        if (addrIn == null || addrIn.length() == 0) {
            throw new Exception("ADDR_IN not set");
        } else if (addrOut == null || addrOut.length() == 0) {
            throw new Exception("ADDR_OUT not set");
        }

        OperatorProcessingInterface operator = new Operator();
        EventManager eventManager = new EventManager(dbType, dbHost, addrIn, addrOut, operator);
    }
}
