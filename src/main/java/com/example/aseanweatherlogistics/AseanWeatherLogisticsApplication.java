package com.example.aseanweatherlogistics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// 开启定时调度：后台气象守护（WeatherWatchdogService）依赖它，
// 让「气象→风险→熔断」不再只能靠前端轮询顺带触发。
@EnableScheduling
public class AseanWeatherLogisticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AseanWeatherLogisticsApplication.class, args);
    }
}
