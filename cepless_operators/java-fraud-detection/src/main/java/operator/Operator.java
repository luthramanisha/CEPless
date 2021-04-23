package operator;

public class Operator implements OperatorProcessingInterface {
    @Override
    public String process(String item) {
        try {
            String[] parts = item.split(",");
            int index = parts[1].indexOf(".");
            if (index == -1) {
                index = parts[1].length();
            }
            String number = parts[1].substring(0, index);
            int filter = 0;
            if (Integer.parseInt(number) > 10) {
                filter = 1;
            }
            //  System.out.println("Amount " + parts[1] + " as Int " + Integer.parseInt(number) + " > 50");
            item = filter + item;
            return item;
        } catch (Exception e) {
		    e.printStackTrace();
		    System.out.println(e);
            System.out.println(e.getMessage());
        }
        return null;
    }
}