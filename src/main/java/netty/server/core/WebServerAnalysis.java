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
	
	private final FullHttpRequest request;
	
	private final ChannelHandlerContext ctx;
	
	private final WebServerMapping mapping;
	
	private final QueryStringDecoder decoder;
	
	private WebServerAnalysis(final FullHttpRequest request,
			final ChannelHandlerContext ctx,
			final WebServerMapping mapping,
			final QueryStringDecoder decoder) {
		this.request = request;
		this.ctx = ctx;
		this.mapping = mapping;
		this.decoder = decoder;
	}
	
	/**
	 * 解析URL，如果存在，返回mapping，否则返回null，外层判断如果为null则不继续执行
	 * 同时判断如果不存在，查看是否为静态文件，如果是，下载，如果不是，404
	 * @return
	 */
	static WebServerAnalysis analysis(final ChannelHandlerContext ctx, final FullHttpRequest request) {
		// 解析uri
		final QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

		// 去url映射中匹配
		final WebServerMapping mapping = WebServerMapping.get(request, decoder.path());

		if (mapping != null)
			return new WebServerAnalysis(request, ctx, mapping, decoder);

		// 这里是重中之重，判断文件是否存在，如果存在，下载，如果不存在，返回404
		WebServerUtil.sendError(ctx, NOT_FOUND);
		return null;
	}
	
	/**
	 * 解析入参并执行，但是不输出
	 * 
	 * @return
	 */
	Object execute(final Map<String, Object> attrubite) throws Exception {
		// 获取入参
		final Map<String, List<String>> parameters = decoder.parameters();

		// 文件缓存
		final List<File> fileCache = new ArrayList<File>();
		final List<String> pathCache = new ArrayList<String>();

		// 解析入参
		final Class<?>[] params = mapping.method.getParameterTypes();
		final Object[] args = new Object[params.length];
		
		// 遍历入参
		for (int i = 0; i < params.length; i++) {
			if (params[i] == ChannelHandlerContext.class) {
				// 入参类型是ChannelHandlerContext
				args[i] = ctx;
			} else if (params[i] == FullHttpRequest.class || params[i] == HttpRequest.class
					|| params[i] == HttpMessage.class || params[i] == HttpObject.class) {
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
				} else if (mapping.names[i].equals("params")) {
					args[i] = parameters;
				}
			} else {
				// 入参类型无法解析
				args[i] = null;
			}
		}

		Object result = mapping.method.invoke(mapping.clazz.newInstance(), args);
		// 如果文件没有被转移，清除文件缓存
		for (int i = 0; i < fileCache.size(); i++)
			if (fileCache.get(i).getPath().equals(pathCache.get(i)))
				fileCache.get(i).delete();
		return result;
	}
	
	/**
	 * 输出结果
	 */
	void write(final Map<String, Object> attrubite, Object result) throws Exception {
		// 解析出参
		final Class<?> resultType = mapping.method.getReturnType();
		
		// 用于返回结果
		final FullHttpResponse fullResponse = new DefaultFullHttpResponse(HTTP_1_1, OK);

		// 出参类型只需要判断3种即可:文件、void、其他，其他所有类型暂时都转做字符串处理
		if (resultType == File.class) {
			// 出参类型是文件
			WebServerUtil.write((File) result, ctx, request);
		} else if (result != null) {
			if (mapping.engine == PageEngine.Velocity) {
				result = VelocityTemp.get(result.toString(), attrubite);

				String zip = WebServerUtil.getProperties("server.properties", "template.zip");

				// 是否代码压缩，模式为否
				if (zip != null && zip.equals("true"))
					result = result.toString()
						.replace("\t", "")
						.replace("\r", "")
						.replace("\n", "");
			}

			final ByteBuf buffer = Unpooled.copiedBuffer(result.toString(), CharsetUtil.UTF_8);
			fullResponse.content().writeBytes(buffer);
			buffer.release();
		}

		if (resultType != File.class) {
			// 不是文件，以文本形式输出
			fullResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
			ctx.writeAndFlush(fullResponse).addListener(ChannelFutureListener.CLOSE);
		}
	}
}