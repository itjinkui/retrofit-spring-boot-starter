package com.github.lianjiatech.retrofit.spring.boot.core;

import com.github.lianjiatech.retrofit.spring.boot.annotation.InterceptMark;
import com.github.lianjiatech.retrofit.spring.boot.annotation.OkHttpClientBuilder;
import com.github.lianjiatech.retrofit.spring.boot.annotation.RetrofitClient;
import com.github.lianjiatech.retrofit.spring.boot.config.RetrofitConfigBean;
import com.github.lianjiatech.retrofit.spring.boot.config.RetrofitProperties;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.*;
import com.github.lianjiatech.retrofit.spring.boot.util.BeanExtendUtils;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import org.slf4j.event.Level;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import retrofit2.CallAdapter;
import retrofit2.Converter;
import retrofit2.Retrofit;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author 陈添明
 */
public class RetrofitFactoryBean<T> implements FactoryBean<T>, EnvironmentAware, ApplicationContextAware {

    private Class<T> retrofitInterface;

    private Environment environment;

    private RetrofitProperties retrofitProperties;

    private RetrofitConfigBean retrofitConfigBean;

    private ApplicationContext applicationContext;

    public RetrofitFactoryBean(Class<T> retrofitInterface) {
        this.retrofitInterface = retrofitInterface;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getObject() throws Exception {
        checkRetrofitInterface(retrofitInterface);
        Retrofit retrofit = getRetrofit(retrofitInterface);
        return retrofit.create(retrofitInterface);
    }

    /**
     * RetrofitInterface检查
     *
     * @param retrofitInterface .
     */
    private void checkRetrofitInterface(Class<T> retrofitInterface) {
        // check class type
        Assert.isTrue(retrofitInterface.isInterface(), "@RetrofitClient只能作用在接口类型上！");
        Method[] methods = retrofitInterface.getMethods();
        for (Method method : methods) {

            Class<?> returnType = method.getReturnType();
            if (method.isAnnotationPresent(OkHttpClientBuilder.class)) {
                Assert.isTrue(returnType.equals(OkHttpClient.Builder.class), "被@OkHttpClientBuilder注解标注的方法，返回值必须是OkHttpClient.Builder");
                Assert.isTrue(Modifier.isStatic(method.getModifiers()), "被@OkHttpClientBuilder注解只能标注在静态方法上");
                continue;
            }

            Assert.isTrue(!void.class.isAssignableFrom(returnType),
                    "不支持使用void关键字做返回类型，请使用java.lang.Void! method=" + method);
            if (retrofitProperties.isDisableVoidReturnType()) {
                Assert.isTrue(!Void.class.isAssignableFrom(returnType),
                        "已配置禁用Void作为返回值，请指定其他返回类型！method=" + method);
            }
        }
    }


    @Override
    public Class<T> getObjectType() {
        return this.retrofitInterface;
    }


    @Override
    public boolean isSingleton() {
        return true;
    }


    /**
     * 获取okhttp3连接池
     *
     * @param retrofitClientInterfaceClass retrofitClient接口类
     * @return okhttp3连接池
     */
    private synchronized okhttp3.ConnectionPool getConnectionPool(Class<?> retrofitClientInterfaceClass) {
        RetrofitClient retrofitClient = retrofitClientInterfaceClass.getAnnotation(RetrofitClient.class);
        String poolName = retrofitClient.poolName();
        Map<String, ConnectionPool> poolRegistry = retrofitConfigBean.getPoolRegistry();
        Assert.notNull(poolRegistry, "poolRegistry不存在！请设置retrofitConfigBean.poolRegistry！");
        ConnectionPool connectionPool = poolRegistry.get(poolName);
        Assert.notNull(connectionPool, "当前poolName对应的连接池不存在！poolName = " + poolName);
        return connectionPool;
    }


    /**
     * 获取OkHttpClient实例，一个接口接口对应一个OkHttpClient
     *
     * @param retrofitClientInterfaceClass retrofitClient接口类
     * @return OkHttpClient实例
     */
    private synchronized OkHttpClient getOkHttpClient(Class<?> retrofitClientInterfaceClass) throws IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        RetrofitClient retrofitClient = retrofitClientInterfaceClass.getAnnotation(RetrofitClient.class);
        Method method = findOkHttpClientBuilderMethod(retrofitClientInterfaceClass);
        OkHttpClient.Builder okHttpClientBuilder;
        if (method != null) {
            okHttpClientBuilder = (OkHttpClient.Builder) method.invoke(null);
        } else {
            okhttp3.ConnectionPool connectionPool = getConnectionPool(retrofitClientInterfaceClass);
            // 构建一个OkHttpClient对象
            okHttpClientBuilder = new OkHttpClient.Builder()
                    .connectTimeout(retrofitClient.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                    .readTimeout(retrofitClient.readTimeoutMs(), TimeUnit.MILLISECONDS)
                    .writeTimeout(retrofitClient.writeTimeoutMs(), TimeUnit.MILLISECONDS)
                    .callTimeout(retrofitClient.callTimeoutMs(), TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(retrofitClient.retryOnConnectionFailure())
                    .followRedirects(retrofitClient.followRedirects())
                    .followSslRedirects(retrofitClient.followSslRedirects())
                    .pingInterval(retrofitClient.pingIntervalMs(), TimeUnit.MILLISECONDS)
                    .connectionPool(connectionPool);
        }
        // 添加接口上注解定义的拦截器
        List<Interceptor> interceptors = new ArrayList<>(findInterceptorByAnnotation(retrofitClientInterfaceClass));
        // 添加全局拦截器
        Collection<BaseGlobalInterceptor> globalInterceptors = retrofitConfigBean.getGlobalInterceptors();
        if (!CollectionUtils.isEmpty(globalInterceptors)) {
            interceptors.addAll(globalInterceptors);
        }
        interceptors.forEach(okHttpClientBuilder::addInterceptor);

        //  http异常信息格式化
        HttpExceptionMessageFormatterInterceptor httpExceptionMessageFormatterInterceptor = retrofitConfigBean.getHttpExceptionMessageFormatterInterceptor();
        if (httpExceptionMessageFormatterInterceptor != null) {
            okHttpClientBuilder.addInterceptor(httpExceptionMessageFormatterInterceptor);
        }

        // 请求重试拦截器
        Interceptor retryInterceptor = retrofitConfigBean.getRetryInterceptor();
        okHttpClientBuilder.addInterceptor(retryInterceptor);

        // 日志打印拦截器
        if (retrofitProperties.isEnableLog() && retrofitClient.enableLog()) {
            Class<? extends BaseLoggingInterceptor> loggingInterceptorClass = retrofitProperties.getLoggingInterceptor();
            Constructor<? extends BaseLoggingInterceptor> constructor = loggingInterceptorClass.getConstructor(Level.class, BaseLoggingInterceptor.LogStrategy.class);
            BaseLoggingInterceptor loggingInterceptor = constructor.newInstance(retrofitClient.logLevel(), retrofitClient.logStrategy());
            okHttpClientBuilder.addNetworkInterceptor(loggingInterceptor);
        }

        Collection<NetworkInterceptor> networkInterceptors = retrofitConfigBean.getNetworkInterceptors();
        if (!CollectionUtils.isEmpty(networkInterceptors)) {
            for (NetworkInterceptor networkInterceptor : networkInterceptors) {
                okHttpClientBuilder.addNetworkInterceptor(networkInterceptor);
            }
        }

        return okHttpClientBuilder.build();
    }

    private Method findOkHttpClientBuilderMethod(Class<?> retrofitClientInterfaceClass) {
        Method[] methods = retrofitClientInterfaceClass.getMethods();
        for (Method method : methods) {
            if (Modifier.isStatic(method.getModifiers())
                    && method.isAnnotationPresent(OkHttpClientBuilder.class)
                    && method.getReturnType().equals(OkHttpClient.Builder.class)) {
                return method;
            }
        }
        return null;
    }


    /**
     * 获取retrofitClient接口类上定义的拦截器集合
     *
     * @param retrofitClientInterfaceClass retrofitClient接口类
     * @return 拦截器实例集合
     */
    @SuppressWarnings("unchecked")
    private List<Interceptor> findInterceptorByAnnotation(Class<?> retrofitClientInterfaceClass) throws InstantiationException, IllegalAccessException {
        Annotation[] classAnnotations = retrofitClientInterfaceClass.getAnnotations();
        List<Interceptor> interceptors = new ArrayList<>();
        // 找出被@InterceptMark标记的注解
        List<Annotation> interceptAnnotations = new ArrayList<>();
        for (Annotation classAnnotation : classAnnotations) {
            Class<? extends Annotation> annotationType = classAnnotation.annotationType();
            if (annotationType.isAnnotationPresent(InterceptMark.class)) {
                interceptAnnotations.add(classAnnotation);
            }
        }
        for (Annotation interceptAnnotation : interceptAnnotations) {
            // 获取注解属性数据
            Map<String, Object> annotationAttributes = AnnotationUtils.getAnnotationAttributes(interceptAnnotation);
            Object handler = annotationAttributes.get("handler");
            Assert.notNull(handler, "@InterceptMark标记的注解必须配置: Class<? extends BasePathMatchInterceptor> handler()");
            Assert.notNull(annotationAttributes.get("include"), "@InterceptMark标记的注解必须配置: String[] include()");
            Assert.notNull(annotationAttributes.get("exclude"), "@InterceptMark标记的注解必须配置: String[] exclude()");
            Class<? extends BasePathMatchInterceptor> interceptorClass = (Class<? extends BasePathMatchInterceptor>) handler;
            BasePathMatchInterceptor interceptor = getInterceptorInstance(interceptorClass);
            Map<String, Object> annotationResolveAttributes = new HashMap<>(8);
            // 占位符属性替换
            annotationAttributes.forEach((key, value) -> {
                if (value instanceof String) {
                    String newValue = environment.resolvePlaceholders((String) value);
                    annotationResolveAttributes.put(key, newValue);
                } else {
                    annotationResolveAttributes.put(key, value);
                }
            });
            // 动态设置属性值
            BeanExtendUtils.populate(interceptor, annotationResolveAttributes);
            interceptors.add(interceptor);
        }
        return interceptors;
    }

    /**
     * 获取路径拦截器实例，优先从spring容器中取。如果spring容器中不存在，则无参构造器实例化一个
     *
     * @param interceptorClass 路径拦截器类的子类，参见@{@link BasePathMatchInterceptor}
     * @return 路径拦截器实例
     */
    private BasePathMatchInterceptor getInterceptorInstance(Class<? extends BasePathMatchInterceptor> interceptorClass) throws IllegalAccessException, InstantiationException {
        // spring bean
        try {
            return applicationContext.getBean(interceptorClass);
        } catch (BeansException e) {
            // spring容器获取失败，反射创建
            return interceptorClass.newInstance();
        }
    }


    /**
     * 获取Retrofit实例，一个retrofitClient接口对应一个Retrofit实例
     *
     * @param retrofitClientInterfaceClass retrofitClient接口类
     * @return Retrofit实例
     */
    private synchronized Retrofit getRetrofit(Class<?> retrofitClientInterfaceClass) throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        RetrofitClient retrofitClient = retrofitClientInterfaceClass.getAnnotation(RetrofitClient.class);
        String baseUrl = retrofitClient.baseUrl();
        // 解析baseUrl占位符
        baseUrl = environment.resolveRequiredPlaceholders(baseUrl);
        OkHttpClient client = getOkHttpClient(retrofitClientInterfaceClass);
        Retrofit.Builder retrofitBuilder = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .validateEagerly(retrofitClient.validateEagerly())
                .client(client);
        // 添加CallAdapter.Factory
        List<CallAdapter.Factory> callAdapterFactories = retrofitConfigBean.getCallAdapterFactories();
        if (!CollectionUtils.isEmpty(callAdapterFactories)) {
            callAdapterFactories.forEach(retrofitBuilder::addCallAdapterFactory);
        }
        // 添加Converter.Factory
        List<Converter.Factory> converterFactories = retrofitConfigBean.getConverterFactories();
        if (!CollectionUtils.isEmpty(converterFactories)) {
            converterFactories.forEach(retrofitBuilder::addConverterFactory);
        }
        return retrofitBuilder.build();
    }


    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        this.retrofitConfigBean = applicationContext.getBean(RetrofitConfigBean.class);
        this.retrofitProperties = retrofitConfigBean.getRetrofitProperties();
    }
}
