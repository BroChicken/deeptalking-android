// ==================== API 请求级重试 ====================
const MAX_API_RETRIES = 3;
const API_RETRY_DELAYS_MS = [1000, 2000, 4000];

// 瞬时失败（网络/超时/429/5xx）值得自动重试；业务 4xx 不重试
function isTransientApiError(error) {
  if (!error || typeof error !== 'object') return false;
  if (error.aborted) return true;
  if (error.network) return true;
  if (error.status) return error.status === 429 || error.status >= 500;
  return false;
}

async function performChatRequestWithRetry(messages, text, loadingMsg, toolState, phase) {
  var retries = 0;
  while (true) {
    try {
      return await performChatRequest(messages, text, loadingMsg, toolState, phase);
    } catch (error) {
      if (retries < MAX_API_RETRIES && isTransientApiError(error)) {
        retries++;
        var delay = API_RETRY_DELAYS_MS[retries - 1] || 4000;
        setActivity('连接中断，正在重试（' + retries + '/' + MAX_API_RETRIES + '）…');
        updateMessageBubble(loadingMsg.id, getDisplayText('连接中断，正在重试…'));
        await new Promise(function(resolve) { setTimeout(resolve, delay); });
        continue;
      }
      throw error;
    }
  }
}

