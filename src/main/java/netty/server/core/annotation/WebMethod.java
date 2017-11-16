package netty.server.core.annotation;

import java.lang.annotation.*;

import netty.server.core.annotation.type.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WebMethod {

	HttpMethod method();
}