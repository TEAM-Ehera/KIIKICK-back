package com.teamehera.kiikick.match.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(annotations = MatchApiController.class)
public class RequestIdResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // 본문을 다시 직렬화하지 않고 메타데이터의 ID를 그대로 사용한다. 멱등 응답은 저장된 헤더를 재생한다.
        if (body instanceof MatchApiResponse.Envelope envelope) {
            response.getHeaders().set(MatchApiResponse.REQUEST_ID_HEADER, envelope.meta().requestId().toString());
        }
        return body;
    }
}
