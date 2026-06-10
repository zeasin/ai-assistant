package com.laoqi.assistant.controller;

import com.laoqi.assistant.entity.Memory;
import com.laoqi.assistant.service.IMemoryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/memory")
public class MemoryController {

    private final IMemoryService memoryService;

    public MemoryController(IMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 记忆管理页面 */
    @GetMapping("")
    public String index(Model model) {
        model.addAttribute("memories", memoryService.listAll());
        model.addAttribute("total", memoryService.count());
        return "memories";
    }

    // ========== API ==========

    /** 搜索记忆 */
    @GetMapping("/api/search")
    @ResponseBody
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "10") int topK) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("data", memoryService.search(q, topK));
        return result;
    }

    /** 列表 */
    @GetMapping("/api/list")
    @ResponseBody
    public Map<String, Object> list() {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("data", memoryService.listAll());
        result.put("total", memoryService.count());
        return result;
    }

    /** 保存记忆 */
    @PostMapping("/api/remember")
    @ResponseBody
    public Map<String, Object> remember(@RequestParam String content,
                                        @RequestParam(defaultValue = "user") String source,
                                        @RequestParam(required = false) List<String> tags) {
        memoryService.remember(content, source, tags);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("message", "已记住");
        return result;
    }

    /** 遗忘（删除） */
    @DeleteMapping("/api/forget/{id}")
    @ResponseBody
    public Map<String, Object> forget(@PathVariable Integer id) {
        memoryService.forget(id);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("message", "已遗忘");
        return result;
    }

    /** 清空所有（批量遗忘） */
    @DeleteMapping("/api/forgetAll")
    @ResponseBody
    public Map<String, Object> forgetAll() {
        for (Memory m : memoryService.listAll()) {
            memoryService.forget(m.getId());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("message", "已全部遗忘");
        return result;
    }
}