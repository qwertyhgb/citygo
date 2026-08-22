package com.citygo.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>注册分页拦截器，配合 {@link com.baomidou.mybatisplus.extension.plugins.pagination.Page}
 * 使用完成物理分页。DbType 使用 3.5.9+ 的枚举写法（如 {@code DbType.MYSQL}），
 * 勿用旧版字符串常量。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 拦截器链，加入 MySQL 分页插件。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

}