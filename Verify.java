import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class Verify {
    public static void main(String[] args) {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        String raw = "admin123";

        // 当前 init.sql 里的哈希
        String current = "$2a$10$z4Wu9NFQg7C5a8KwL6hZeehUcsK0U.OvseVTOJBic162lTe3uI0Nm";
        // 旧哈希
        String old = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

        System.out.println("current.matches(admin123) = " + enc.matches(raw, current));
        System.out.println("old.matches(admin123)     = " + enc.matches(raw, old));

        // 现场重新生成一次，看看每次生成的哈希不同（BCrypt 带随机 salt）
        String fresh = enc.encode(raw);
        System.out.println("fresh encode              = " + fresh);
        System.out.println("fresh.matches(admin123)   = " + enc.matches(raw, fresh));
    }
}
