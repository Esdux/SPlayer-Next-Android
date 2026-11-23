/** 返回键拦截栈：后进先出，返回时从栈顶开始消费 */
const interceptors: (() => boolean)[] = [];

/**
 * 注册返回拦截
 * @param handler - 返回 true 表示已消费本次返回
 * @returns 解绑函数
 */
export const registerBackInterceptor = (handler: () => boolean): (() => void) => {
  interceptors.push(handler);
  return () => {
    const index = interceptors.indexOf(handler);
    if (index >= 0) interceptors.splice(index, 1);
  };
};

/**
 * 交给最上层拦截器处理
 * @returns 是否已被消费
 */
export const consumeBack = (): boolean => {
  // 从栈顶向下找第一个真正消费的处理器，避免残留处理器（返回 false）阻断下层
  for (let i = interceptors.length - 1; i >= 0; i -= 1) {
    if (interceptors[i]()) return true;
  }
  return false;
};
