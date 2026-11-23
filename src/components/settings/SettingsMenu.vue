<script setup lang="ts">
import type { SettingCategory } from "@/types/settings-schema";
import type { SMenuItem } from "@/components/ui/SMenu.vue";

const props = defineProps<{
  categories: SettingCategory[];
  activeId: string;
  /** 折叠为仅图标的窄栏 */
  collapsed?: boolean;
}>();

const emit = defineEmits<{
  select: [id: string];
}>();

const { t } = useI18n();

const menuItems = computed<SMenuItem[]>(() =>
  props.categories.map((cat) => ({
    key: cat.id,
    label: t(`settings.group.${cat.id}`),
    icon: cat.icon,
  })),
);
</script>

<template>
  <SMenu
    :items="menuItems"
    :model-value="activeId"
    :collapsed="collapsed"
    center-active-on-mount
    @select="emit('select', $event)"
  />
</template>
