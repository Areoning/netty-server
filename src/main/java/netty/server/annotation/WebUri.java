package netty.server.annotation;

import java.lang.annotation.*;

import netty.server.annotation.type.PageEngine;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Inherited // 可继承
public @interface WebUri {

	String value() default "";
	
	PageEngine engine() default PageEngine.None;
}