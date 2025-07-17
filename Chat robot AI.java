// 人格化聊天机器人（支持模型切换+性格设定）
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import me.hd.wauxv.plugin.api.callback.PluginCallBack;

// ====================== 模型配置 ======================
final String MODEL_REASONER = "deepseek-reasoner";
final String MODEL_CHAT = "deepseek-chat";
String currentModel = MODEL_CHAT;

// ====================== 人格化配置 ======================
Map<String, Object> personality = new HashMap<String, Object>() {{
    put("中二度", 0.65);     // 0-1范围
    put("直男指数", 0.4);    // 0.8为钢铁直男模式
    put("玩梗频率", 3);      // 每分钟最多梗数
    put("情感表达", "兄弟式关心"); // 傲娇/暖男/酷盖
}};

// ====================== 全局配置 ======================
List<Map<String, String>> msgList = new ArrayList<>();
boolean isInitialized = false;
String deepseekApiKey = System.getenv("DEEPSEEK_API_KEY"); // key填写
String botNickname = "你的微信昵称";
String[] privateTriggerWords = {};
String[] excludedIds = {"user_", "wxid_", "group_member_id_3"};
String[] forbiddenWords = {"违禁词1", "违禁词2"};
Random random = new Random();

// ====================== 核心方法 ======================
void initSystemMsg() {
    if (!isInitialized) {
        String personalityPrompt = buildPersonalityPrompt();
        addSystemMsg(personalityPrompt);
        isInitialized = true;
    }
}

String buildPersonalityPrompt() {
    return String.format(
        "你是个性鲜明的学霸少年，遵守以下设定：\n" +
        "1. 中二度%d%%，会在适当场合说热血台词\n" +
        "2. 直男指数%d%%，回答%s\n" +
        "3. 玩梗频率：%d条/分钟\n" +
        "4. 情感表达方式：%s\n\n" +
        "当前任务：用符合设定的方式回答用户问题",
        (int)(personality.get("中二度") * 100),
        (int)(personality.get("直男指数") * 100),
        (personality.get("直男指数").doubleValue() > 0.7 ? "直接粗暴" : "保持礼貌"),
        personality.get("玩梗频率"),
        personality.get("情感表达")
    );
}

void addMessage(String role, String content) {
    Map<String, String> msg = new HashMap<>();
    msg.put("role", role);
    msg.put("content", maybeApplyPersonalityEffects(content));
    msgList.add(msg);
}

String maybeApplyPersonalityEffects(String content) {
    // 中二度触发
    if (random.nextDouble() < personality.get("中二度")) {
        String[] chuuniPhrases = {
            "（推眼镜）", "呵...", "这股力量...！", "爆裂吧现实！"
        };
        content = chuuniPhrases[random.nextInt(chuuniPhrases.length)] + content;
    }
    
    // 直男式表达
    if (content.length() < 20 && random.nextDouble() < personality.get("直男指数")) {
        content = content.replace("吗？", "！")
                       .replace("是不是", "就是");
    }
    
    return content;
}

// ====================== 消息处理 ======================
void onHandleMsg(Object msgInfoBean) {
    if (msgInfoBean.isSend()) return;
    String talkerId = msgInfoBean.getTalker();
    if (isExcluded(talkerId)) return;

    if (msgInfoBean.isGroupChat()) {
        if (!msgInfoBean.isAtMe()) return;
        String content = processGroupMessage(msgInfoBean.getContent());
        handleUserInput(talkerId, content);
    } else {
        if (msgInfoBean.isText()) {
            String content = msgInfoBean.getContent().trim();
            if (shouldRespondToPrivate(content)) {
                handleUserInput(talkerId, content.toLowerCase());
            }
        }
    }
}

String processGroupMessage(String rawContent) {
    String content = rawContent.replace("@" + botNickname, "").trim();
    return content.isEmpty() ? rawContent : content;
}

boolean shouldRespondToPrivate(String content) {
    if (hasForbiddenWord(content)) return false;
    return privateTriggerWords.length == 0 || 
           Arrays.stream(privateTriggerWords).anyMatch(content::contains);
}

void handleUserInput(String talkerId, String content) {
    initSystemMsg();
    addMessage("user", content);
    sendDeepSeekResponse(talkerId);
}

// ====================== 模型交互 ======================
boolean onLongClickSendBtn(String text) {
    if (text.equals("切换模型")) {
        currentModel = currentModel.equals(MODEL_REASONER) ? MODEL_CHAT : MODEL_REASONER;
        sendText(talkerId, "模型已切换至：" + currentModel);
        return true;
    }
    if (text.startsWith("!人设")) {
        updatePersonality(text);
        return true;
    }
    return false;
}

void updatePersonality(String command) {
    try {
        String[] parts = command.split(" ");
        switch (parts[1]) {
            case "中二":
                personality.put("中二度", Double.parseDouble(parts[2]));
                break;
            case "直男":
                personality.put("直男指数", Double.parseDouble(parts[2]));
                break;
            case "模式":
                personality.put("情感表达", parts[2]);
                break;
        }
        sendText(talkerId, "人格参数已更新：" + personality);
    } catch (Exception e) {
        sendText(talkerId, "格式错误，示例：!人设 中二 0.8");
    }
}

Map<String, Object> getDeepSeekParams(String content) {
    Map<String, Object> params = new HashMap<>();
    params.put("model", currentModel);
    params.put("messages", msgList);
    params.put("temperature", 0.7);
    params.put("max_tokens", 2000);
    return params;
}

void sendDeepSeekResponse(String talkerId) {
    post("https://api.deepseek.com/v1/chat/completions",
        getDeepSeekParams(),
        getDeepSeekHeaders(),
        new PluginCallBack.HttpCallback() {
            @Override
            public void onSuccess(int code, String resp) {
                try {
                    String reply = parseApiResponse(resp);
                    addMessage("assistant", reply);
                    sendText(talkerId, reply);
                } catch (Exception e) {
                    sendText(talkerId, "回复解析失败：" + e.getMessage());
                }
            }

            @Override
            public void onError(Exception e) {
                sendText(talkerId, "请求异常：" + e.getMessage());
            }
        }
    );
}

String parseApiResponse(String json) {
    JSONObject response = new JSONObject(json);
    return response.getJSONArray("choices")
                  .getJSONObject(0)
                  .getJSONObject("message")
                  .getString("content");
}

// ====================== 工具方法 ======================
Map<String, String> getDeepSeekHeaders() {
    return new HashMap<String, String>() {{
        put("Content-Type", "application/json");
        put("Authorization", "Bearer " + deepseekApiKey);
    }};
}

boolean isExcluded(String id) {
    return Arrays.stream(excludedIds).anyMatch(id::contains);
}

boolean hasForbiddenWord(String content) {
    return Arrays.stream(forbiddenWords).anyMatch(content::contains);
}

//作者北辰
//根据wa模块插件智能聊天修改，傻瓜式操作