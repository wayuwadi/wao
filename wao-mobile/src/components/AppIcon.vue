<script setup lang="ts">
import {
  Sun,
  CheckCheck,
  CalendarDays,
  NotebookPen,
  Plus,
  ArrowUpRight,
  ChevronLeft,
  ChevronRight,
  Search,
  X,
  Check,
  RefreshCw,
  LogOut,
  ArrowRight,
  SlidersHorizontal,
  Circle,
  CloudCheck,
  AlertCircle,
  Sparkles,
  Calendar,
  FilePenLine,
  Gamepad2,
} from "@lucide/vue";
const icons = {
  sun: Sun,
  tasks: CheckCheck,
  calendar: CalendarDays,
  notes: NotebookPen,
  plus: Plus,
  arrow: ArrowUpRight,
  left: ChevronLeft,
  right: ChevronRight,
  search: Search,
  close: X,
  check: Check,
  refresh: RefreshCw,
  logout: LogOut,
  next: ArrowRight,
  filter: SlidersHorizontal,
  circle: Circle,
  cloud: CloudCheck,
  alert: AlertCircle,
  sparkles: Sparkles,
  date: Calendar,
  edit: FilePenLine,
  game: Gamepad2,
};
defineProps<{ name: keyof typeof icons; size?: number }>();
</script>
<template>
  <component
    :is="icons[name]"
    :size="size ?? 20"
    :stroke-width="1.7"
    aria-hidden="true"
  />
</template>
