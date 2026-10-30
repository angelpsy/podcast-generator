export type Content = {version: number; volume: string; immersion: string; intent: string; audience: string};
export const defaultContent: Content = {version: 1, volume: 'DETAILED', immersion: 'EXPLANATORY', intent: '', audience: ''};
export const profiles: Record<string, string> = {ru_standard: 'Русский', ru_simple: 'Простой русский', en_simple_ste: 'Простой английский'};
export const volumes: Record<string, string> = {BRIEF: 'Кратко · 150–250 слов', STANDARD: 'Стандартно · 350–550', DETAILED: 'Подробно · 700–1100', DEEP: 'Углублённо · 1200–1800'};
export const immersionLabels: Record<string, string> = {OVERVIEW: 'Обзорное', EXPLANATORY: 'Объясняющее', DEEP: 'Глубокое'};
