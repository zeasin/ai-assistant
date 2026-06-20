package com.laoqi.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Service
public class ExamService {

    private static final Logger log = LoggerFactory.getLogger(ExamService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final DataSource dataSource;
    private final LlmService llmService;

    public ExamService(DataSource dataSource, LlmService llmService) {
        this.dataSource = dataSource;
        this.llmService = llmService;
    }

    public long recognizeAndSave(byte[] imageBytes, String imageType, String modelName, String paperName) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String systemPrompt = "你是一个专业的试卷识别助手。请识别图片中的所有题目，严格按照以下JSON格式返回：\n" +
                "{\"questions\":[{\"seq\":1,\"type\":\"选择题|填空题|判断题|解答题|计算题|简答题\",\"content\":\"题目内容\"," +
                "\"options\":\"A.选项1\\nB.选项2\\nC.选项3\\nD.选项4\",\"answer\":\"正确答案\"," +
                "\"explanation\":\"详细解析\",\"knowledge\":\"知识点标签\",\"difficulty\":1-5}]\n" +
                "只返回JSON，不要其他内容。如果图片模糊无法识别，标注reason字段说明。";

        String reply = llmService.chatWithImage(systemPrompt,
                "请识别这张试卷图片中的所有题目，返回JSON格式。", base64Image, imageType, modelName);

        if (reply == null || reply.isEmpty()) {
            throw new RuntimeException("AI 未返回结果");
        }

        List<Map<String, Object>> questions = parseQuestionsJson(reply);
        if (questions.isEmpty()) {
            throw new RuntimeException("未能从AI响应中解析出题目");
        }

        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long paperId = insertPaper(paperName, "", imageType, modelName, "completed", now);

        for (Map<String, Object> q : questions) {
            insertQuestion(paperId, q, now);
        }

        return paperId;
    }

    private List<Map<String, Object>> parseQuestionsJson(String json) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "");
            }
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
            Map<String, Object> root = objectMapper.readValue(cleaned, new TypeReference<>() {});
            Object qs = root.get("questions");
            if (qs instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> rawMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) rawMap;
                        Map<String, Object> q = new LinkedHashMap<>();
                        q.put("seq", m.getOrDefault("seq", 0));
                        q.put("type", m.getOrDefault("type", "未知"));
                        q.put("content", m.getOrDefault("content", ""));
                        q.put("options", m.getOrDefault("options", ""));
                        q.put("answer", m.getOrDefault("answer", ""));
                        q.put("explanation", m.getOrDefault("explanation", ""));
                        q.put("knowledge", m.getOrDefault("knowledge", ""));
                        q.put("difficulty", m.getOrDefault("difficulty", 3));
                        result.add(q);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[试卷] 解析JSON失败: {}", e.getMessage());
        }
        return List.of();
    }

    private long insertPaper(String name, String imagePath, String imageType, String model, String status, String now) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO exam_papers (name, image_path, image_type, model, status, created_at) VALUES (?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, imagePath);
            ps.setString(3, imageType);
            ps.setString(4, model);
            ps.setString(5, status);
            ps.setString(6, now);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            log.error("[试卷] 插入试卷失败", e);
        }
        return -1;
    }

    private void insertQuestion(long paperId, Map<String, Object> q, String now) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO exam_questions (paper_id, seq_num, question_type, content, options, answer, explanation, knowledge_tags, difficulty, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, paperId);
            ps.setInt(2, q.containsKey("seq") ? ((Number) q.get("seq")).intValue() : 0);
            ps.setString(3, String.valueOf(q.getOrDefault("type", "未知")));
            ps.setString(4, String.valueOf(q.getOrDefault("content", "")));
            ps.setString(5, String.valueOf(q.getOrDefault("options", "")));
            ps.setString(6, String.valueOf(q.getOrDefault("answer", "")));
            ps.setString(7, String.valueOf(q.getOrDefault("explanation", "")));
            ps.setString(8, String.valueOf(q.getOrDefault("knowledge", "")));
            ps.setInt(9, q.containsKey("difficulty") ? ((Number) q.get("difficulty")).intValue() : 3);
            ps.setString(10, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[试卷] 插入题目失败", e);
        }
    }

    public List<Map<String, Object>> getPapers() {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT p.*, (SELECT COUNT(*) FROM exam_questions WHERE paper_id = p.id) AS question_count FROM exam_papers p ORDER BY p.id DESC")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("name", rs.getString("name"));
                m.put("status", rs.getString("status"));
                m.put("model", rs.getString("model"));
                m.put("createdAt", rs.getString("created_at"));
                m.put("questionCount", rs.getInt("question_count"));
                list.add(m);
            }
        } catch (SQLException e) {
            log.error("[试卷] 查询试卷列表失败", e);
        }
        return list;
    }

    public boolean deletePaper(long paperId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM exam_questions WHERE paper_id = ?")) {
                ps.setLong(1, paperId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM exam_papers WHERE id = ?")) {
                ps.setLong(1, paperId);
                ps.executeUpdate();
            }
            conn.commit();
            return true;
        } catch (SQLException e) {
            log.error("[试卷] 删除试卷失败", e);
            return false;
        }
    }

    public List<Map<String, Object>> getQuestions(Long paperId, String type, String tag) {
        List<Map<String, Object>> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM exam_questions WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (paperId != null) {
            sql.append(" AND paper_id = ?");
            params.add(paperId);
        }
        if (type != null && !type.isEmpty()) {
            sql.append(" AND question_type = ?");
            params.add(type);
        }
        if (tag != null && !tag.isEmpty()) {
            sql.append(" AND knowledge_tags LIKE ?");
            params.add("%" + tag + "%");
        }
        sql.append(" ORDER BY paper_id, seq_num");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("paperId", rs.getLong("paper_id"));
                m.put("seqNum", rs.getInt("seq_num"));
                m.put("questionType", rs.getString("question_type"));
                m.put("content", rs.getString("content"));
                m.put("options", rs.getString("options"));
                m.put("answer", rs.getString("answer"));
                m.put("explanation", rs.getString("explanation"));
                m.put("knowledgeTags", rs.getString("knowledge_tags"));
                m.put("difficulty", rs.getInt("difficulty"));
                list.add(m);
            }
        } catch (SQLException e) {
            log.error("[试卷] 查询题目失败", e);
        }
        return list;
    }

    public boolean deleteQuestion(long questionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM exam_questions WHERE id = ?")) {
            ps.setLong(1, questionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("[试卷] 删除题目失败", e);
            return false;
        }
    }

    public List<Map<String, Object>> startPractice(int count, String type) {
        StringBuilder sql = new StringBuilder("SELECT * FROM exam_questions WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (type != null && !type.isEmpty()) {
            sql.append(" AND question_type = ?");
            params.add(type);
        }
        sql.append(" ORDER BY RANDOM() LIMIT ?");
        params.add(count);

        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("seqNum", rs.getInt("seq_num"));
                m.put("questionType", rs.getString("question_type"));
                m.put("content", rs.getString("content"));
                m.put("options", rs.getString("options"));
                m.put("answer", rs.getString("answer"));
                m.put("explanation", rs.getString("explanation"));
                m.put("knowledgeTags", rs.getString("knowledge_tags"));
                m.put("difficulty", rs.getInt("difficulty"));
                list.add(m);
            }
        } catch (SQLException e) {
            log.error("[试卷] 开始练习失败", e);
        }
        return list;
    }

    public boolean submitAnswer(long questionId, String userAnswer) {
        String correctAnswer = getCorrectAnswer(questionId);
        if (correctAnswer == null) return false;

        boolean isCorrect = normalizeAnswer(userAnswer).equals(normalizeAnswer(correctAnswer));
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO exam_practices (question_id, user_answer, is_correct, created_at) VALUES (?,?,?,?)")) {
            ps.setLong(1, questionId);
            ps.setString(2, userAnswer);
            ps.setInt(3, isCorrect ? 1 : 0);
            ps.setString(4, now);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.error("[试卷] 提交答案失败", e);
            return false;
        }
    }

    private String getCorrectAnswer(long questionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT answer FROM exam_questions WHERE id = ?")) {
            ps.setLong(1, questionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("answer");
        } catch (SQLException e) {
            log.error("[试卷] 查询正确答案失败", e);
        }
        return null;
    }

    private String normalizeAnswer(String answer) {
        if (answer == null) return "";
        String s = answer.trim().toLowerCase();
        s = s.replaceAll("[\\s　]+", "");
        s = s.replaceAll("^[A-Aa-a][.、．。]\\s*", "");
        return s;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM exam_questions");
            if (rs.next()) stats.put("totalQuestions", rs.getInt("cnt"));

            rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM exam_practices");
            if (rs.next()) stats.put("totalPracticed", rs.getInt("cnt"));

            rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM exam_practices WHERE is_correct = 1");
            if (rs.next()) stats.put("correctCount", rs.getInt("cnt"));

            int total = (int) stats.getOrDefault("totalPracticed", 0);
            int correct = (int) stats.getOrDefault("correctCount", 0);
            stats.put("correctRate", total > 0 ? Math.round(correct * 100.0 / total) : 0);
            stats.put("wrongCount", total - correct);

            List<Map<String, Object>> byType = new ArrayList<>();
            rs = stmt.executeQuery(
                    "SELECT q.question_type AS type, " +
                    "COUNT(DISTINCT p.id) AS total, " +
                    "SUM(CASE WHEN p.is_correct = 1 THEN 1 ELSE 0 END) AS correct " +
                    "FROM exam_practices p JOIN exam_questions q ON p.question_id = q.id " +
                    "GROUP BY q.question_type");
            while (rs.next()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("type", rs.getString("type"));
                int tTotal = rs.getInt("total");
                int tCorrect = rs.getInt("correct");
                t.put("total", tTotal);
                t.put("correct", tCorrect);
                t.put("rate", tTotal > 0 ? Math.round(tCorrect * 100.0 / tTotal) : 0);
                byType.add(t);
            }
            stats.put("byType", byType);
        } catch (SQLException e) {
            log.error("[试卷] 查询统计失败", e);
        }
        return stats;
    }
}
