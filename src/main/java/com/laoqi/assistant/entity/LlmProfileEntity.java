package com.laoqi.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("llm_profiles")
public class LlmProfileEntity {

    /** 模型类型：text（文本/聊天）, multimodal（多模态/识图）, embedding（向量/语义检索） */
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_MULTIMODAL = "multimodal";
    public static final String TYPE_EMBEDDING = "embedding";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String apiKey;
    private String baseUrl;
    private String model;
    private Integer timeout;
    private Boolean isDefault;
    /** @deprecated 改用 modelType */
    @Deprecated
    private Boolean visionSupport;
    /** 模型类型：text / multimodal / embedding，默认 text */
    private String modelType;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getTimeout() { return timeout; }
    public void setTimeout(Integer timeout) { this.timeout = timeout; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    @Deprecated
    public Boolean getVisionSupport() { return visionSupport; }
    @Deprecated
    public void setVisionSupport(Boolean visionSupport) { this.visionSupport = visionSupport; }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }

    /** 向后兼容：用 modelType 判断是否多模态 */
    public boolean isMultimodal() {
        return TYPE_MULTIMODAL.equals(modelType)
                || Boolean.TRUE.equals(visionSupport);
    }
}
