package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


// @SpringBootApplication = 三个注解合一：
// 1. @Configuration     — 这是配置类
// 2. @EnableAutoConfiguration — 自动装配（扫描依赖自动配置）
// 3. @ComponentScan    — 扫描当前包下所有 @Component/@Service/@Controller
//
// 类比 Python Flask：app = Flask(__name__)
@SpringBootApplication
public class TravelAgentApplication {

    // Java 程序入口永远是 main 方法
    // 类比 Python：if __name__ == '__main__': app.run()
    public static void main(String[] args) {
        SpringApplication.run(TravelAgentApplication.class, args);
        System.out.println("✈️  Travel Agent is running on http://localhost:8080");
    }
}
