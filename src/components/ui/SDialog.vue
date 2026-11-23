<script setup lang="ts">
import type { CSSProperties } from "vue";
import { usePopupZIndex } from "@/composables/useZIndex";
import { registerBackInterceptor } from "@/services/backStack";

export interface SDialogProps {
  /** 控制打开状态（v-model:open） */
  open?: boolean;
  /** 是否为模态（阻止外部交互） */
  modal?: boolean;
  /** 标题（无障碍必需，不传则隐藏标题区） */
  title?: string;
  /** 描述文本 */
  description?: string;
  /** 是否显示关闭按钮 */
  closable?: boolean;
  /** 封面主题模式（播放器内使用） */
  cover?: boolean;
  /** 宽度，支持 CSS 值（默认 460px） */
  width?: string;
  /** 高度，支持 CSS 值（默认 auto，受 max-h 限制） */
  height?: string;
  /** 距视口顶部偏移 */
  top?: string;
  /** 内容区自定义样式 */
  contentStyle?: string | CSSProperties;
  /** 首次打开时再挂载内容 */
  lazy?: boolean;
  /** 关闭后销毁内容 */
  destroyOnClose?: boolean;
  /** 自定义固定层级 */
  zIndex?: number;
  /** 竖屏手机：以底部弹层形式从下方贴边弹出 */
  mobileSheet?: boolean;
}

const props = withDefaults(defineProps<SDialogProps>(), {
  modal: true,
  closable: true,
  cover: false,
  width: "460px",
  height: "auto",
  lazy: true,
  destroyOnClose: false,
  mobileSheet: false,
});

const DESTROY_DELAY_MS = 180;

/** 竖屏手机判定：仅竖屏弹层模式使用 */
const isPhoneViewport = useMediaQuery("(max-width: 640px)");
const sheet = computed(() => props.mobileSheet && isPhoneViewport.value);

/** 竖屏可用高度：扣掉状态栏、手势区与四周间距 */
const phoneMaxHeight =
  "calc(100dvh - var(--android-safe-top, 0px) - max(var(--android-safe-bottom, 0px), var(--android-ime-bottom, 0px)) - 32px)";

const containerStyle = computed(() => {
  if (sheet.value) {
    // 底部弹层：贴底、左右各留 4px，并避让状态栏与手势区
    return {
      width: "calc(100vw - 8px)",
      height: props.height === "auto" ? undefined : phoneMaxHeight,
      maxHeight: phoneMaxHeight,
      top: "auto",
      // 键盘弹出时整体上抬，避免输入框被盖住
      bottom:
        "calc(max(var(--android-safe-bottom, 0px), var(--android-ime-bottom, 0px)) + 24px)",
      left: "4px",
    };
  }
  if (isPhoneViewport.value) {
    // 竖屏非弹层：宽度不超出屏幕并留间距，指定高度时撑满可用上下空间
    return {
      width: `min(${props.width}, calc(100vw - 16px))`,
      height: props.height === "auto" ? undefined : phoneMaxHeight,
      maxHeight: phoneMaxHeight,
      top: "calc(var(--android-safe-top, 0px) + 8px)",
      left: "50%",
      transform: "translateX(-50%)",
    };
  }
  return {
    width: props.width,
    height: props.height === "auto" ? undefined : props.height,
    maxHeight: props.height === "auto" ? "85vh" : undefined,
    top: props.top,
  };
});

const emit = defineEmits<{
  "update:open": [value: boolean];
}>();

/** 位置依赖安全区 CSS 变量，首帧可能还没算稳，先隐藏一帧再显示避免跳变 */
const layoutReady = ref(false);

const isOpen = ref(props.open ?? false);

/** 返回键拦截解绑函数 */
let offBack: (() => void) | undefined;
const mounted = ref((!props.lazy && !props.destroyOnClose) || isOpen.value);
let destroyTimer: ReturnType<typeof setTimeout> | undefined;

const { zIndex: activeZIndex, onOpenChange } = usePopupZIndex(toRef(props, "zIndex"));

/** 更新内容挂载状态与动态层级 */
const syncMounted = (open: boolean): void => {
  if (destroyTimer) {
    clearTimeout(destroyTimer);
    destroyTimer = undefined;
  }

  onOpenChange(open);

  if (open) {
    mounted.value = true;
    return;
  }

  if (!props.destroyOnClose) return;

  destroyTimer = setTimeout(() => {
    mounted.value = false;
    destroyTimer = undefined;
  }, DESTROY_DELAY_MS);
};

watch(
  () => props.open,
  (val) => {
    if (val !== undefined) isOpen.value = val;
  },
);

watch(
  isOpen,
  (value) => {
    syncMounted(value);
    if (!value) {
      layoutReady.value = false;
      offBack?.();
      offBack = undefined;
      return;
    }
    if (!offBack) {
      offBack = registerBackInterceptor(() => {
        if (!isOpen.value) return false;
        setOpen(false);
        return true;
      });
    }
    // 等两帧：一帧让 DOM 挂载，一帧让安全区变量与尺寸计算落到最终值
    requestAnimationFrame(() => requestAnimationFrame(() => { layoutReady.value = true; }));
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  offBack?.();
  offBack = undefined;
  if (destroyTimer) clearTimeout(destroyTimer);
});

const setOpen = (val: boolean): void => {
  isOpen.value = val;
  emit("update:open", val);
};
</script>

<template>
  <DialogRoot :open="isOpen" :modal="modal" @update:open="setOpen">
    <!-- 触发器插槽 -->
    <DialogTrigger v-if="$slots.trigger" as-child>
      <slot name="trigger" />
    </DialogTrigger>

    <DialogPortal v-if="mounted">
      <!-- 遮罩层 -->
      <DialogOverlay
        data-dialog-overlay
        :style="{ zIndex: activeZIndex }"
        :class="[
          'fixed inset-0 data-[state=open]:animate-overlay-in data-[state=closed]:animate-overlay-out',
          cover ? 'bg-black/50' : 'bg-black/40',
        ]"
      />
      <DialogContent
        data-dialog-content
        :style="[containerStyle, { zIndex: activeZIndex }, layoutReady ? {} : { visibility: 'hidden' }]"
        :class="[
          'fixed',
          sheet ? '' : 'left-1/2',
          sheet ? '' : 'md:-translate-x-1/2',
          sheet || top || isPhoneViewport ? '' : 'top-1/2 -translate-y-1/2',
          sheet ? 'rounded-t-2xl rounded-b-2xl' : 'rounded-xl',
          'shadow-xl overflow-hidden',
          'flex flex-col',
          // 竖屏弹层的位置由 top/bottom 与 translateX 决定，位移动画会与之冲突导致先错位再归位
          sheet || isPhoneViewport
            ? 'transition-opacity duration-150 data-[state=open]:opacity-100 data-[state=closed]:opacity-0'
            : top
              ? 'data-[state=open]:animate-dialog-in-top data-[state=closed]:animate-dialog-out-top'
              : 'data-[state=open]:animate-dialog-in data-[state=closed]:animate-dialog-out',
          'focus:outline-none',
          cover
            ? 'bg-black/55 backdrop-blur-xl backdrop-saturate-160 border border-solid border-white/10 text-cover'
            : 'bg-surface-alt border border-solid border-outline-variant/30 text-on-surface',
        ]"
      >
        <!-- 标题 + 描述 -->
        <div v-if="title" class="shrink-0 px-5 pt-4 pb-3 pr-12">
          <DialogTitle class="text-lg font-semibold">{{ title }}</DialogTitle>
          <DialogDescription
            v-if="description"
            :class="['text-xs mt-1', cover ? 'text-cover/50' : 'text-on-surface/50']"
          >
            {{ description }}
          </DialogDescription>
          <DialogDescription v-else class="sr-only">{{ title }}</DialogDescription>
        </div>
        <!-- 无障碍 -->
        <template v-else>
          <DialogTitle class="sr-only">Dialog</DialogTitle>
          <DialogDescription class="sr-only" />
        </template>
        <!-- 内容 -->
        <div
          class="flex-1 min-h-0 overflow-y-auto text-sm"
          :class="[
            height === 'auto' && 'px-5',
            height === 'auto' && !title && 'pt-4',
            height === 'auto' && !$slots.footer && 'pb-4',
          ]"
          :style="contentStyle"
        >
          <slot />
        </div>
        <!-- 底部操作 -->
        <div
          v-if="$slots.footer"
          class="shrink-0 px-5 pt-3 pb-4 flex items-center justify-end gap-2"
        >
          <slot name="footer" :close="() => setOpen(false)" />
        </div>
        <!-- 关闭按钮 -->
        <DialogClose v-if="closable" as-child>
          <SButton
            :type="cover ? 'cover' : 'default'"
            variant="ghost"
            size="small"
            circle
            class="absolute top-3 right-3"
          >
            <template #icon>
              <IconLucideX />
            </template>
          </SButton>
        </DialogClose>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
