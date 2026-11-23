import { onBeforeUnmount, watch } from "vue";
import { registerBackInterceptor } from "@/services/backStack";

/**
 * 让浮层接住系统返回键：打开时注册拦截，返回优先关闭浮层
 * @param getOpen - 返回当前是否打开
 * @param close - 关闭浮层的动作
 */
export const useBackGuard = (getOpen: () => boolean, close: () => void): void => {
  let off: (() => void) | undefined;

  const sync = (open: boolean): void => {
    if (open && !off) {
      off = registerBackInterceptor(() => {
        if (!getOpen()) return false;
        close();
        return true;
      });
      return;
    }
    if (!open && off) {
      off();
      off = undefined;
    }
  };

  watch(getOpen, sync, { immediate: true });

  onBeforeUnmount(() => {
    off?.();
    off = undefined;
  });
};
