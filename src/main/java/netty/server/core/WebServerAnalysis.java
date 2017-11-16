package netty.server.core;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.*;
import netty.server.core.annotation.type.*;
import netty.server.core.engine.*;

import java.io.*;
import java.util.*;

/**
 * URL解析类
 */
class WebServerAnalysis {
	
	static boolean analysis(final ChannelHandlerContext ctx, final FullHttpRequest request, final Map<String, Object> attrubite) throws Exception {
		// 解析uri
		final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
		final Map<String, List<String>> parameters = decoder.parameters();
		
		// 去url映射中匹配
		final WebServerMapping mapping = WebServerMapping.get(request, decoder.path());

		if (mapping == null)
			return false;
		
		// 文件缓存
		final List<File> fileCache = new ArrayList<File>();
		final List<String> pathCache = new ArrayList<String>();
		
		// 解析入参
		final Class<?>[] params = mapping.method.getParameterTypes();
		final Object[] args = new Object[params.length];
		{
			// 遍历入参
			for (int i = 0; i < params.length; i++) {
				if (params[i] == ChannelHandlerContext.class) {
					// 入参类型是ChannelHandlerContext
					args[i] = ctx;
				} else if (params[i] == FullHttpRequest.class
						|| params[i] == HttpRequest.class
						|| params[i] == HttpMessage.class
						|| params[i] == HttpObject.class) {
					// 入参类型是FullHttpRequest
					args[i] = request;
				} else if (params[i] == String.class) {
					// 入参类型是String
					final List<String> list = parameters.get(mapping.names[i]);
					args[i] = WebServerUtil.listToString(list);
				} else if (params[i] == File.class) {
					// 入参类型是File
					final File file = WebServerUtil.readFile(ctx, request, mapping.names[i]);
					if (file != null) {
						fileCache.add(file);
						pathCache.add(file.getPath());
					}
					args[i] = file;
				} else if (params[i] == Map.class) {
					// 入参类型是Map，用于接收返回值
					if (mapping.names[i].equals("attr")) {
						args[i] = attrubite;
					} else if(mapping.names[i].equals("params")) {
						args[i] = parameters;
					}
				} else {
					// 入参类型无法解析
					args[i] = null;
				}
			}
		}
		
		// 解析出参
		final Class<?> resultType = mapping.method.getReturnType();
		{
			// 用于返回结果
			final FullHttpResponse fullResponse = new DefaultFullHttpResponse(HTTP_1_1, OK);
			
			// 出参类型只需要判断3种即可:文件、void、其他，其他所有类型暂时都转做字符串处理
			if(resultType == File.class){
				// 出参类型是文件
				final File file = (File) mapping.method.invoke(mapping.clazz.newInstance(), args);
				
				WebServerUtil.write(file, ctx, request);
			} else if (resultType != void.class) {
				// 出参类型是文件外的其他类型
				Object result = mapping.method.invoke(mapping.clazz.newInstance(), args);
				
				if (result != null) {
					if (mapping.engine == PageEngine.Velocity) {
						result = VelocityTemp.get(result.toString(), attrubite);
					}
					
					final ByteBuf buffer = Unpooled.copiedBuffer(result.toString(), CharsetUtil.UTF_8);
					fullResponse.content().writeBytes(buffer);
					buffer.release();
				}
			}
			
			if (resultType != File.class) {
				// 不是文件，以文本形式输出
				fullResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
				ctx.writeAndFlush(fullResponse).addListener(ChannelFutureListener.CLOSE);
			}
		}
		
		// 如果文件没有被转移，清除文件缓存
		for (int i = 0; i < fileCache.size(); i++)
			if (fileCache.get(i).getPath().equals(pathCache.get(i)))
				fileCache.get(i).delete();

		return true;
	}
}