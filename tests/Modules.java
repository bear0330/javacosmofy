import java.beans.PropertyChangeEvent;

public final class Modules {
  public static void main(String[] args) {
    System.out.println(new PropertyChangeEvent("source", "name", "old", "new").getPropertyName());
  }
}
