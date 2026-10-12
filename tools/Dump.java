import dev.calvin.droidpad.HidDescriptor;
public class Dump {
  public static void main(String[] a) {
    byte[] d = HidDescriptor.build(660, 1400);
    StringBuilder sb = new StringBuilder();
    for (byte b : d) sb.append(String.format("%02x ", b));
    System.out.println(sb.toString().trim());
    System.err.println("bytes=" + d.length + " report=" + HidDescriptor.REPORT_BYTES);
  }
}
