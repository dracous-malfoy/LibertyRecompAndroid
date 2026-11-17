#include "input/gamepad_touch_overlay.h"

#include <algorithm>
#include <array>
#include <cfloat>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

#include <SDL3/SDL_stdinc.h>
#include <SDL3/SDL_touch.h>
#if defined(__ANDROID__)
#include <SDL3/SDL_system.h>
#endif

#include <rex/cvar.h>
#include <rex/input/absolute_pointer.h>
#include <rex/input/input.h>

REXCVAR_DEFINE_STRING(gta4_touch_layout, "gamepad", "GTA IV/Input/Touch",
                      "On-screen touch controls: gamepad (a fixed Xbox 360 controller) or context "
                      "(the context-sensitive layout)")
    .allowed({"gamepad", "context"});

namespace gta4::input {
namespace {

enum class Kind : uint8_t { kStick, kButton, kTrigger, kArrow };

struct Control {
  Kind kind = Kind::kButton;
  float x = 0.0f, y = 0.0f, r = 0.0f;
  uint16_t button = 0;
  uint8_t index = 0;
  const char* label = "";
  ImU32 label_color = 0;
};

constexpr size_t kControlCount = 20;
constexpr std::array<const char*, kControlCount> kControlIds = {
    "left_stick", "right_stick", "dpad_up", "dpad_down", "dpad_left", "dpad_right",
    "lt", "lb", "l3", "a", "b", "x", "y", "rt", "rb", "r3", "back", "start", "", ""};
constexpr size_t kLeftStick = 0;
constexpr size_t kRightStick = 1;
constexpr float kDeadZone = 0.12f;

constexpr ImU32 kFill = IM_COL32(90, 90, 90, 55);
constexpr ImU32 kFillHeld = IM_COL32(200, 200, 200, 105);
constexpr ImU32 kRing = IM_COL32(215, 215, 215, 105);
constexpr ImU32 kLabel = IM_COL32(230, 230, 230, 150);
constexpr ImU32 kKnob = IM_COL32(225, 225, 225, 130);

ImU32 FadeColor(ImU32 color, float opacity) {
  const float factor = std::isfinite(opacity) ? std::clamp(opacity, 0.0f, 1.5f) : 1.0f;
  const uint32_t alpha = (color >> IM_COL32_A_SHIFT) & 0xFFu;
  const uint32_t scaled = uint32_t(std::clamp(float(alpha) * factor, 0.0f, 255.0f));
  return (color & ~IM_COL32_A_MASK) | (scaled << IM_COL32_A_SHIFT);
}

struct Finger { int control = -1; };
struct LayoutEdit { float dx = 0.0f, dy = 0.0f, scale = 1.0f, opacity = 1.0f; bool hidden = false; };

struct State {
  std::mutex mutex;
  bool visible = false;
  bool layout_loaded = false;
  bool layout_file_existed = false;
  std::filesystem::file_time_type layout_mtime{};
  uint32_t frame_counter = 0;
  std::array<LayoutEdit, kControlCount> layout{};
  std::array<Control, kControlCount> controls{};
  std::array<bool, kControlCount> held{};
  ImVec2 stick_origin[2] = {{0, 0}, {0, 0}};
  float stick_x[2] = {0, 0}, stick_y[2] = {0, 0};
  std::unordered_map<uint64_t, Finger> fingers;
};

State& state() { static State s; return s; }

std::filesystem::path LayoutFilePath() {
  std::filesystem::path root;
  if (const char* xdg = std::getenv("XDG_DATA_HOME"); xdg && *xdg) root = xdg;
#if defined(__ANDROID__)
  if (root.empty()) {
    if (const char* external = SDL_GetAndroidExternalStoragePath(); external && *external) root = external;
  }
#endif
  return root.empty() ? std::filesystem::path{} : root / "LibertyRecomp" / "xbox_touch_layout.txt";
}

void LoadLayout(State& s, bool force) {
  const auto path = LayoutFilePath();
  if (path.empty()) { s.layout_loaded = true; return; }
  std::error_code ec;
  const auto mtime = std::filesystem::last_write_time(path, ec);
  const bool exists = !ec;
  if (!force && s.layout_loaded && exists == s.layout_file_existed && (!exists || mtime == s.layout_mtime)) return;
  s.layout_loaded = true; s.layout_file_existed = exists; s.layout_mtime = mtime;
  s.layout.fill({});
  if (!exists) return;
  std::ifstream file(path); std::string line; unsigned lines = 0;
  while (lines++ < 512 && std::getline(file, line)) {
    if (line.size() > 256) continue;
    std::istringstream input(line); std::string id; LayoutEdit edit; int hidden = 0;
    if (!(input >> id >> edit.dx >> edit.dy >> edit.scale >> hidden) ||
        !std::isfinite(edit.dx) || !std::isfinite(edit.dy) || !std::isfinite(edit.scale)) continue;
    float opacity = 1.0f;
    if (input >> opacity && std::isfinite(opacity)) edit.opacity = std::clamp(opacity, 0.15f, 1.5f);
    edit.dx = std::clamp(edit.dx, -2000.0f, 2000.0f); edit.dy = std::clamp(edit.dy, -800.0f, 800.0f);
    edit.scale = std::clamp(edit.scale, 0.35f, 3.0f); edit.hidden = hidden != 0;
    for (size_t i = 0; i < kControlCount; ++i) {
      if (kControlIds[i][0] != '\0' && id == kControlIds[i]) { s.layout[i] = edit; break; }
    }
  }
}

std::array<Control, kControlCount> BuildLayout(float w, float h, const std::array<LayoutEdit, kControlCount>& edits) {
  std::array<Control, kControlCount> c{}; size_t n = 0; const float unit = h / 737.0f;
  const auto add = [&](Kind kind, float x, float y, float r, uint16_t button, uint8_t index, const char* label, ImU32 color = 0) {
    const size_t idx = n++; const auto& edit = edits[idx];
    if (edit.hidden) { c[idx] = Control{kind, 0.0f, 0.0f, 0.0f, button, index, label, color}; return; }
    const float sr = std::min(std::min(w, h) * 0.45f, std::max(4.0f, r * edit.scale));
    c[idx] = Control{kind, std::clamp(x + edit.dx * unit, sr, w - sr), std::clamp(y + edit.dy * unit, sr, h - sr), sr, button, index, label, color};
  };
  const float lx = 0.12f * w;
  add(Kind::kStick, lx, 0.72f * h, 0.105f * h, 0, 0, "L");
  add(Kind::kStick, w * 0.75f, 0.60f * h, 0.095f * h, 0, 1, "R");
  const float dx = lx + 0.26f * h, dy = 0.86f * h, arm = 0.078f * h, dr = 0.042f * h;
  add(Kind::kArrow, dx, dy - arm, dr, rex::input::X_INPUT_GAMEPAD_DPAD_UP, 0, "");
  add(Kind::kArrow, dx, dy + arm, dr, rex::input::X_INPUT_GAMEPAD_DPAD_DOWN, 1, "");
  add(Kind::kArrow, dx - arm, dy, dr, rex::input::X_INPUT_GAMEPAD_DPAD_LEFT, 2, "");
  add(Kind::kArrow, dx + arm, dy, dr, rex::input::X_INPUT_GAMEPAD_DPAD_RIGHT, 3, "");
  const float br = 0.048f * h, small = 0.036f * h;
  add(Kind::kTrigger, lx, 0.355f * h, br, 0, 0, "LT");
  add(Kind::kButton, lx, 0.475f * h, br, rex::input::X_INPUT_GAMEPAD_LEFT_SHOULDER, 0, "LB");
  add(Kind::kButton, lx + 0.115f * h, 0.475f * h, small, rex::input::X_INPUT_GAMEPAD_LEFT_THUMB, 0, "L3");
  const float fx = w - 0.52f * h, fy = 0.80f * h, fs = 0.097f * h, fr = 0.052f * h;
  add(Kind::kButton, fx, fy + fs, fr, rex::input::X_INPUT_GAMEPAD_A, 0, "A", IM_COL32(110, 205, 80, 170));
  add(Kind::kButton, fx + fs, fy, fr, rex::input::X_INPUT_GAMEPAD_B, 0, "B", IM_COL32(235, 75, 70, 170));
  add(Kind::kButton, fx - fs, fy, fr, rex::input::X_INPUT_GAMEPAD_X, 0, "X", IM_COL32(75, 145, 240, 170));
  add(Kind::kButton, fx, fy - fs, fr, rex::input::X_INPUT_GAMEPAD_Y, 0, "Y", IM_COL32(245, 205, 50, 170));
  const float rx = w - 0.17f * h;
  add(Kind::kTrigger, rx, 0.47f * h, br, 0, 1, "RT");
  add(Kind::kButton, rx, 0.59f * h, br, rex::input::X_INPUT_GAMEPAD_RIGHT_SHOULDER, 0, "RB");
  add(Kind::kButton, rx - 0.115f * h, 0.59f * h, small, rex::input::X_INPUT_GAMEPAD_RIGHT_THUMB, 0, "R3");
  const float sr = 0.036f * h;
  add(Kind::kButton, 0.44f * w, 0.075f * h, sr, rex::input::X_INPUT_GAMEPAD_BACK, 0, "BACK");
  add(Kind::kButton, 0.56f * w, 0.075f * h, sr, rex::input::X_INPUT_GAMEPAD_START, 0, "START");
  return c;
}

int HitTest(const std::array<Control, kControlCount>& controls, float px, float py, float w) {
  int best = -1; float best_distance = FLT_MAX;
  for (size_t i = 0; i < controls.size(); ++i) {
    const auto& control = controls[i];
    if (control.r <= 0.0f || control.kind == Kind::kStick) continue;
    const float capture = control.r * 1.25f;
    const float distance = std::hypot(px - control.x, py - control.y);
    if (distance <= capture && distance / capture < best_distance) { best_distance = distance / capture; best = int(i); }
  }
  if (best >= 0) return best;
  const int stick = int(px < w * 0.5f ? kLeftStick : kRightStick);
  return controls[stick].r > 0.0f ? stick : -1;
}

ImVec2 LabelOrigin(ImFont* font, float size, const char* label, ImVec2 center) {
  const ImVec2 text = font->CalcTextSizeA(size, FLT_MAX, 0.0f, label);
  float y = center.y - text.y * 0.5f, x = center.x - text.x * 0.5f;
  if (ImFontBaked* baked = font->GetFontBaked(size)) {
    if (const ImFontGlyph* glyph = baked->FindGlyphNoFallback(ImWchar(label[0]))) {
      const float scale = baked->Size > 0.0f ? size / baked->Size : 1.0f;
      y = center.y - (glyph->Y0 + glyph->Y1) * 0.5f * scale;
      if (label[1] == 0) x = center.x - (glyph->X0 + glyph->X1) * 0.5f * scale;
    }
  }
  return ImVec2(x, y);
}

void Draw(ImDrawList* draw, ImFont* font, float font_size, const State& s) {
  for (size_t i = 0; i < s.controls.size(); ++i) {
    const auto& control = s.controls[i];
    if (control.r <= 0.0f) continue;
    const float op = s.layout[i].opacity, radius = control.r;
    const bool active = s.held[i];
    if (control.kind == Kind::kStick) {
      if (control.index == 1 && !active) continue;
      const ImVec2 center = active ? s.stick_origin[control.index] : ImVec2(control.x, control.y);
      draw->AddCircleFilled(center, radius, FadeColor(kFill, op), 48);
      draw->AddCircle(center, radius, FadeColor(kRing, op), 48, std::max(1.0f, radius * 0.025f));
      const ImVec2 knob(center.x + s.stick_x[control.index] * radius * 0.55f, center.y + s.stick_y[control.index] * radius * 0.55f);
      draw->AddCircleFilled(knob, radius * 0.34f, FadeColor(kKnob, op), 32);
      continue;
    }
    const ImVec2 center(control.x, control.y);
    draw->AddCircleFilled(center, radius, FadeColor(active ? kFillHeld : kFill, op), 48);
    draw->AddCircle(center, radius, FadeColor(kRing, op), 48, std::max(1.0f, radius * 0.025f));
    if (control.kind == Kind::kArrow) {
      const float t = radius * 0.42f; ImVec2 a, b, c;
      switch (control.index) {
        case 0: a = {center.x, center.y - t}; b = {center.x - t, center.y + t * 0.6f}; c = {center.x + t, center.y + t * 0.6f}; break;
        case 1: a = {center.x, center.y + t}; b = {center.x + t, center.y - t * 0.6f}; c = {center.x - t, center.y - t * 0.6f}; break;
        case 2: a = {center.x - t, center.y}; b = {center.x + t * 0.6f, center.y + t}; c = {center.x + t * 0.6f, center.y - t}; break;
        default: a = {center.x + t, center.y}; b = {center.x - t * 0.6f, center.y - t}; c = {center.x - t * 0.6f, center.y + t}; break;
      }
      draw->AddTriangleFilled(a, b, c, FadeColor(kLabel, op));
      continue;
    }
    const ImVec2 natural = font->CalcTextSizeA(font_size, FLT_MAX, 0.0f, control.label);
    const float size = control.label[1] == 0 ? radius * 0.95f : natural.x > 0.0f ? std::min(radius * 0.62f, font_size * radius * 1.35f / natural.x) : font_size;
    draw->AddText(font, size, LabelOrigin(font, size, control.label, center), FadeColor(control.label_color ? control.label_color : kLabel, op), control.label);
  }
}
}  // namespace

bool GamepadTouchLayoutSelected() noexcept { return REXCVAR_GET(gta4_touch_layout) == "gamepad"; }
bool GamepadTouchOverlayVisible() noexcept {
  if (!GamepadTouchLayoutSelected()) return false;
  std::lock_guard lock(state().mutex);
  return state().visible;
}

bool UpdateAndDrawGamepadTouchOverlay(ImDrawList* draw, ImFont* font, float font_size, float width, float height) noexcept {
  auto& s = state();
  const bool visible = GamepadTouchLayoutSelected() && rex::input::TouchControlsVisible() && width > 0.0f && height > 0.0f;
  std::lock_guard lock(s.mutex);
  if (!visible) { s.visible = false; s.fingers.clear(); s.held.fill(false); s.stick_x[0] = s.stick_x[1] = s.stick_y[0] = s.stick_y[1] = 0.0f; return false; }
  const bool becoming_visible = !s.visible;
  s.visible = true;
  if (!s.layout_loaded || becoming_visible || (s.frame_counter++ % 90) == 0) LoadLayout(s, !s.layout_loaded);
  s.controls = BuildLayout(width, height, s.layout);
  std::unordered_map<uint64_t, ImVec2> touches;
  int device_count = 0;
  if (SDL_TouchID* devices = SDL_GetTouchDevices(&device_count)) {
    for (int d = 0; d < device_count; ++d) {
      int finger_count = 0;
      if (SDL_Finger** fingers = SDL_GetTouchFingers(devices[d], &finger_count)) {
        for (int f = 0; f < finger_count; ++f) touches[(uint64_t(devices[d]) << 32) ^ uint64_t(fingers[f]->id)] = ImVec2(fingers[f]->x * width, fingers[f]->y * height);
        SDL_free(fingers);
      }
    }
    SDL_free(devices);
  }
  for (auto it = s.fingers.begin(); it != s.fingers.end();) it = touches.count(it->first) ? std::next(it) : s.fingers.erase(it);
  bool stick_taken[2] = {false, false};
  for (const auto& [key, finger] : s.fingers) if (finger.control >= 0 && s.controls[finger.control].kind == Kind::kStick) stick_taken[s.controls[finger.control].index] = true;
  s.held.fill(false);
  bool stick_owned[2] = {false, false};
  for (const auto& [key, position] : touches) {
    auto found = s.fingers.find(key);
    if (found == s.fingers.end()) {
      int control = HitTest(s.controls, position.x, position.y, width);
      if (control >= 0 && s.controls[control].kind == Kind::kStick) {
        const auto& stick = s.controls[control];
        if (stick_taken[stick.index]) control = -1;
        else { stick_taken[stick.index] = true; s.stick_origin[stick.index] = ImVec2(std::clamp(position.x, stick.r, width - stick.r), std::clamp(position.y, stick.r, height - stick.r)); }
      }
      found = s.fingers.emplace(key, Finger{control}).first;
    }
    const int control_index = found->second.control;
    if (control_index < 0) continue;
    const Control& control = s.controls[control_index];
    s.held[control_index] = true;
    if (control.kind != Kind::kStick) continue;
    const ImVec2 origin = s.stick_origin[control.index];
    float x = (position.x - origin.x) / (control.r * 0.85f), y = (position.y - origin.y) / (control.r * 0.85f);
    const float length = std::hypot(x, y);
    if (length > 1.0f) { x /= length; y /= length; }
    s.stick_x[control.index] = x; s.stick_y[control.index] = y; stick_owned[control.index] = true;
  }
  for (int i = 0; i < 2; ++i) if (!stick_owned[i]) s.stick_x[i] = s.stick_y[i] = 0.0f;
  if (draw && font && font_size > 0.0f) Draw(draw, font, font_size, s);
  return true;
}

bool ReadGamepadTouchOverlay(uint32_t user, rex::input::X_INPUT_GAMEPAD* output) noexcept {
  if (!output || user != 0) return false;
  auto& s = state(); std::lock_guard lock(s.mutex);
  if (!s.visible) return false;
  uint16_t buttons = 0; uint8_t triggers[2] = {0, 0};
  for (size_t i = 0; i < s.controls.size(); ++i) {
    if (!s.held[i]) continue;
    if (s.controls[i].kind == Kind::kTrigger) triggers[s.controls[i].index] = 255;
    else if (s.controls[i].kind != Kind::kStick) buttons |= s.controls[i].button;
  }
  const auto stick = [&](int i, int16_t* x_out, int16_t* y_out) {
    const float x = s.stick_x[i], y = s.stick_y[i], length = std::hypot(x, y);
    if (length < kDeadZone) { *x_out = *y_out = 0; return; }
    const float scale = std::min((length - kDeadZone) / (1.0f - kDeadZone), 1.0f) / length;
    *x_out = int16_t(std::clamp(x * scale, -1.0f, 1.0f) * 32767.0f);
    *y_out = int16_t(std::clamp(-y * scale, -1.0f, 1.0f) * 32767.0f);
  };
  int16_t lx = 0, ly = 0, rx = 0, ry = 0;
  stick(0, &lx, &ly); stick(1, &rx, &ry);
  *output = {}; output->buttons = buttons; output->left_trigger = triggers[0]; output->right_trigger = triggers[1];
  output->thumb_lx = lx; output->thumb_ly = ly; output->thumb_rx = rx; output->thumb_ry = ry;
  return true;
}
}  // namespace gta4::input
