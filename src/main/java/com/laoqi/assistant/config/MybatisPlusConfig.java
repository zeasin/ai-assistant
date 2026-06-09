package com.laoqi.assistant.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.laoqi.assistant.mapper")
public class MybatisPlusConfig {

}