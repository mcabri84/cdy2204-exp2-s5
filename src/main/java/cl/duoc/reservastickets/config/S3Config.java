package cl.duoc.reservastickets.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {
    @Bean
    S3Client s3Client(@Value("${app.aws.region}") String region) {
        // En EC2 se utiliza el rol IAM de la instancia, sin claves en el codigo.
        return S3Client.builder().region(Region.of(region)).build();
    }
}
