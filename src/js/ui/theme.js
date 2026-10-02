// ==================== Theme Management ====================
function setTheme(theme) {
  state.activeTheme = theme;
  applyTheme();
  scheduleSave();
  toggleThemeMenu(false);
}

function applyTheme() {
  document.body.classList.remove('theme-black', 'theme-blue', 'theme-yellow');
  if (state.activeTheme) {
    document.body.classList.add(state.activeTheme);
  }
  document.querySelectorAll('.theme-option').forEach(function(btn) {
    btn.classList.remove('active-theme');
  });
  var activeBtn = document.querySelector('.theme-option[data-theme="' + (state.activeTheme || '') + '"]');
  if (activeBtn) activeBtn.classList.add('active-theme');
}

function toggleThemeMenu(force) {
  var menu = document.getElementById('themeMenu');
  var toggle = document.getElementById('themeMenuToggle');
  if (!menu || !toggle) return;
  var shouldOpen = typeof force === 'boolean' ? force : menu.classList.contains('hidden');
  menu.classList.toggle('hidden', !shouldOpen);
  toggle.setAttribute('aria-expanded', shouldOpen ? 'true' : 'false');
}

