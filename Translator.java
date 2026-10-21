package com.alternative_studios.newswipe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Google Cloud Translation API(Basic, v2) 클라이언트. 외부 라이브러리 없이 HttpURLConnection만 쓴다.
 * 반드시 백그라운드 스레드에서 부른다. API 키는 사용자가 설정에 직접 넣은 값이며 앱에 들어 있지 않다.
 * 무료 구간(월 50만 자)을 지키려고 한 번에 보내는 글자 수를 MAX_CHARS로 막는다.
 */
public final class Translator {
    /** 한 번에 보낼 수 있는 최대 글자 수 (v2 권장 한도). */
    public static final int MAX_CHARS = 5000;
    private static final String ENDPOINT = "https://translation.googleapis.com/language/translate/v2";

    /** 번역 결과. detected는 원문 언어 코드(자동 감지). */
    public static final class Result {
        public final String text, detected;

        Result(String text, String detected) {
            this.text = text;
            this.detected = detected;
        }
    }

    /** 사용자에게 그대로 보여 줘도 되는 오류. */
    public static final class TranslateException extends Exception {
        public TranslateException(String message) {
            super(message);
        }
    }

    /** 지원 언어 (코드, 이름). 설정의 목표 언어 선택에 쓴다. */
    public static final String[][] LANGUAGES = {
            {"en", "영어"}, {"ko", "한국어"}, {"ja", "일본어"}, {"zh-CN", "중국어(간체)"}, {"zh-TW", "중국어(번체)"},
            {"es", "스페인어"}, {"fr", "프랑스어"}, {"de", "독일어"}, {"ru", "러시아어"}, {"vi", "베트남어"},
            {"th", "태국어"}, {"id", "인도네시아어"}, {"it", "이탈리아어"}, {"pt", "포르투갈어"}, {"ar", "아랍어"},
    };

    private Translator() {
    }

    public static String languageName(String code) {
        for (String[] l : LANGUAGES) if (l[0].equalsIgnoreCase(code)) return l[1];
        return code;
    }

    /** source는 null이면 자동 감지. */
    public static Result translate(String apiKey, String text, String source, String target) throws TranslateException {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new TranslateException("설정에서 API 키를 입력해 주세요");
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("q", text);
            body.put("target", target);
            body.put("format", "text");
            if (source != null) body.put("source", source);
            URL url = new URL(ENDPOINT + "?key=" + java.net.URLEncoder.encode(apiKey.trim(), "UTF-8"));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new TranslateException(errorMessage(code, resp));
            JSONObject first = new JSONObject(resp).getJSONObject("data").getJSONArray("translations").getJSONObject(0);
            String out = unescapeHtml(first.getString("translatedText"));
            return new Result(out, first.optString("detectedSourceLanguage", null));
        } catch (TranslateException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new TranslateException("인터넷 연결을 확인해 주세요");
        } catch (Exception e) {
            throw new TranslateException("번역 결과를 읽지 못했습니다");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String errorMessage(int code, String resp) {
        String reason = "";
        try {
            reason = new JSONObject(resp).getJSONObject("error").optString("message", "");
        } catch (Exception ignored) {
        }
        if (code == 400 && reason.toLowerCase(java.util.Locale.ROOT).contains("api key")) return "API 키가 올바르지 않습니다";
        if (code == 403) return "API 키 권한 또는 결제 설정을 확인해 주세요 (Cloud Translation API 사용 설정)";
        if (code == 429) return "요청 한도를 넘었습니다. 잠시 후 다시 시도해 주세요";
        return "번역에 실패했습니다 (" + code + ")";
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** format=text여도 일부 응답에 HTML 엔티티가 섞이는 경우를 대비해 흔한 것만 푼다. */
    static String unescapeHtml(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
