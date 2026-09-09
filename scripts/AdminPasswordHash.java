import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Reads only stdin. Never pass an administrator password as a command argument. */
class AdminPasswordHash {
  public static void main(String[] args)throws Exception {
    String password=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();
    if(password==null||password.length()<12||password.getBytes(StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("Password must be at least 12 characters and at most 72 UTF-8 bytes");
    System.out.println(new BCryptPasswordEncoder(12).encode(password));
  }
}
