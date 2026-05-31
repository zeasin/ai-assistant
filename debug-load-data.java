
// 简单测试一下
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class DebugLoadData {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("D:\\projects\\richie_learning_notes\\客户管理\\data");
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};
        
        System.out.println("Files in directory:");
        Files.list(dir).filter(f -> f.getFileName().toString().endsWith(".json")).sorted().forEach(f -> {
            System.out.println("- " + f.getFileName().toString());
        });
        
        System.out.println("\nLoading each file:");
        Files.list(dir).filter(f -> f.getFileName().toString().endsWith(".json")).sorted().forEach(f -> {
            String name = f.getFileName().toString();
            System.out.println("\n=== " + name + " ===");
            try {
                Map<String, Object> data = mapper.readValue(f.toFile(), mapType);
                for (String key : data.keySet()) {
                    Object val = data.get(key);
                    System.out.println("- " + key + " (type: " + (val == null ? "null" : val.getClass().getSimpleName()) + ")");
                    if (val instanceof List) {
                        List list = (List) val;
                        System.out.println("  List size: " + list.size());
                    }
                }
            } catch (Exception e) {
                System.err.println("ERROR: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}
