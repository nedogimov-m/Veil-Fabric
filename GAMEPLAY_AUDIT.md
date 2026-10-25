# Veil: native gameplay/rendering audit — 25 октября 2026

Проверен полный установленный Minecraft 26.3: настоящий мир, production Veil из Sable, native Vulkan-клиент и отдельный тестовый мод без подмены производственных классов. В перечисленных сценариях отдельного подтверждённого дефекта production-кода Veil не найдено. Обнаруженные в его редакторе падение инспектора Sable и ошибки ориентации/обрезки ImGuiMC исправлены в соответствующих проектах. В новый Veil включён исправленный ImGuiMC.

## Проверенное поведение

| Сценарий | Фактический результат |
| --- | --- |
| Редактор | Реальная F6 открывает/закрывает overlay. Все 11 включённых зарегистрированных инспекторов показаны, отрисованы и скрыты через публичный editor API. Среди них framebuffer, shader, texture, resource, light, particle, post, device, profiler, demo и Sable sublevel inspector. Это не проверка каждого вложенного menu/action. |
| Ресурсы и resize | Настоящий SDL resize до 960×540 и Minecraft reload при открытом framebuffer inspector сохраняют установленный UI. Sable inspector проверен с пустой сеткой, настоящим телом, после его удаления и с узким окном. |
| Framebuffer ownership | На настоящем Minecraft RenderTarget Mixin проверены resize/replacement/destroy: borrowed views прекращают действие, отдельные владельцы переживают закрытие wrapper, удаление wrapper возвращает native storage. Прозрачные clears проверены чтением GPU. Все manager attachments остаются живыми после resize 1000×620 и reload. |
| Свет | Направленный, точечный и площадной свет дают ожидаемые HDR-пиксели; additive accumulation, независимые instance payloads, aligned area stride и reverse depth проверены. Настоящая R8 voxel grid проходит двунаправленный 64³ DDA, high-Z indexing, смещённое начало, замену на пустую сетку и отказ неверного upload. |
| Частицы | Настоящий instance layout 104 байта, вычитание больших координат до float, billboard orientation, UV/color/light, translucent/additive blend и reverse depth. Неверный диапазон instances отклонён до draw. Процедурные trail vertices отделяются от исходного mutable capture и дают ожидаемые пиксели в native feature scene. |
| Bloom | Реальная RGBA16F emission, заимствованная глубина, отменённая подготовка, callback после submission и накопление нескольких draw. Загружен исходный семистадийный `core/bloom` JSON: blur/tone mapping, resolve, сохранение caller depth и итоговая RGBA0 очистка. Контрольные RGB: `[82,114,77,115]`. |
| Текстуры/экспорт | Настоящий typed texture inventory, строки/alpha 2D, mip sampling, независимый вертикальный flip PNG, HDR clamp, depth grayscale и integer preview. Проверены шесть cubemap faces/panorama, array layers, one-layer array identity и отдельные PNG слоёв. Асинхронный PNG остаётся точным после немедленного освобождения источника; caller resources сохраняются. |

Свет/частицы/bloom используют контролируемые offscreen/identity fixtures **в полном установленном мире**. Они работают через production API и GPU, внутри поддерживаемого native scene scope, затем возвращают ресурсы настоящему миру. Они не заменяют проход по каждому пользовательскому эффекту в каждой сцене. Три дополнительные shader fixture resources не перекрывают production assets.

## Сборки и доказательства

Итоговый Veil: **`84d660f71338c3839f7d677001f9dbca44673cbebfc42f018f1a0caec24d6dfb`**. Встроенный ImGuiMC: **`c1071a1e436c943a416d863fc0c9a3615514b930ea43aa68b6e4aa77dd477e85`**. Обновлённый hash Veil вызван включением исправленной зависимости; собственные production Java/ресурсы Veil не изменены.

Принятые профили в `../Aeronautics/build/porting/packaged-smoke/`:

- `foundation-2026-10-25-final-{editor,graphics,veil-passes}`: полный комплект с Sable `0bee89fd954771ec0bfbcf8902346c85a26b9678cc77fe822ca1a6d5f7c0f15b`.
- `foundation-2026-10-25-release-editor`: обновлённый Sable `46dea722bab24466f45c0babb1c5d7e1d9731542e4488636824e8f02febdae74`, тот же Veil/ImGuiMC.

В каждом `client/` сохранены `inputs.json`, `runtime.json`, журналы, отдельный тестовый JAR, исходники/хэши драйвера, мир и изображения. `veil-passes/client/texture-exports/` хранит проверенные PNG. Поздние `composited-screenshots/` включают ImGui поверх мира; ранний стандартный захват GameTest не включал этот поздний слой. Baseline-падение Sable сохранено в `foundation-2026-10-25-veil-editor-baseline`. Первоначальный GPU fixture без native scene scope был отклонён с `Prepare native source-block snapshots`; исправлена подготовка теста, production-код ради него не менялся.

`assemble check` прошёл; выполнены 5 unit tests. Java 25, Loader 0.19.5, Fabric API 0.165.0+26.3, Apple M4/macOS, Vulkan/MoltenVK; Fabric development-флаги присутствуют, Sodium отсутствует. Не заявлены другие ОС/драйверы, все shader injections, полный цикл создания/сохранения каждого ресурса редактора или большие пользовательские наборы эффектов.

Повторение: [общий native runner](https://github.com/nedogimov-m/sable-fabric/blob/26.3/GAMEPLAY_AUDIT.md), `--case editor --case graphics --case veil-passes`. При необходимости `--property foundation.passes=lights|particles|bloom|textures` изолирует GPU-подгруппу. Все временные production snapshots удаляются после выхода клиента; исходные hashes и scopes сохраняются. Veil уже вложен в Sable, дополнительный JAR при установке не нужен.
