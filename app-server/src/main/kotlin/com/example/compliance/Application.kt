package com.example.compliance

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["com.example.compliance"])
@EntityScan(basePackages = ["com.example.compliance"])
@EnableJpaRepositories(basePackages = ["com.example.compliance"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
