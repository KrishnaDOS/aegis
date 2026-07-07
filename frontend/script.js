// ============================================================
// AEGIS — script.js
// Auth page (index.html): tab switching, validation, messages
// ============================================================

// --- Tab Switcher ---
function switchTab(tab) {
  const loginForm    = document.getElementById('login-form');
  const registerForm = document.getElementById('register-form');
  const tabs         = document.querySelectorAll('.tab-btn');

  if (tab === 'login') {
    loginForm.classList.remove('hidden');
    registerForm.classList.add('hidden');
    tabs[0].classList.add('active');
    tabs[1].classList.remove('active');
  } else {
    registerForm.classList.remove('hidden');
    loginForm.classList.add('hidden');
    tabs[1].classList.add('active');
    tabs[0].classList.remove('active');
  }
}

// --- Show inline message (error or success) ---
function showMessage(elementId, text, type) {
  const box = document.getElementById(elementId);
  if (!box) return;
  box.textContent = text;
  box.className = 'form-message ' + type; // 'error' or 'success'
  box.classList.remove('hidden');
}

function hideMessage(elementId) {
  const box = document.getElementById(elementId);
  if (box) box.classList.add('hidden');
}

// --- Login: client-side check before POST ---
function handleLogin(event) {
  event.preventDefault();
  const username = document.getElementById('login-user').value.trim();
  const password = document.getElementById('login-pass').value;

  if (!username || !password) {
    showMessage('login-message', 'Please fill in all fields.', 'error');
    return;
  }

  hideMessage('login-message');
  window.location.href = 'groups.html';
}

// --- Register: client-side check before POST ---
function handleRegister(event) {
  event.preventDefault();
  const username = document.getElementById('reg-username').value.trim();
  const email    = document.getElementById('reg-email').value.trim();
  const password = document.getElementById('reg-pass').value;
  const confirm  = document.getElementById('reg-confirm').value;

  if (!username || !email || !password || !confirm) {
    showMessage('register-message', 'Please fill in all fields.', 'error');
    return;
  }

  if (username.length < 3) {
    showMessage('register-message', 'Username must be at least 3 characters.', 'error');
    return;
  }

  if (password.length < 8) {
    showMessage('register-message', 'Password must be at least 8 characters.', 'error');
    return;
  }

  if (password !== confirm) {
    showMessage('register-message', 'Passwords do not match.', 'error');
    return;
  }

  hideMessage('register-message');
  showMessage('register-message', 'Account created! (backend not connected yet)', 'success');
}

// --- Read URL params to display server-side messages ---
// PHP redirects back with ?error=... or ?success=... and ?tab=login/register
function readURLParams() {
  const params  = new URLSearchParams(window.location.search);
  const tab     = params.get('tab');
  const error   = params.get('error');
  const success = params.get('success');

  // Open the correct tab
  if (tab === 'register') {
    switchTab('register');
  } else {
    switchTab('login');
  }

  // Show error or success message in the right form
  if (error) {
    const targetId = tab === 'register' ? 'register-message' : 'login-message';
    showMessage(targetId, decodeURIComponent(error), 'error');
  }

  if (success) {
    showMessage('login-message', decodeURIComponent(success), 'success');
  }
}

// --- Audit log page ---
function loadAuditGroup() {
  const params = new URLSearchParams(window.location.search);
  const group  = params.get('group') || 'Unknown Group';
  const el     = document.getElementById('audit-group-name');
  if (el) el.textContent = group;
}

// --- Run on page load ---
document.addEventListener('DOMContentLoaded', function () {
  readURLParams();
  loadAuditGroup();
});
function filterLogs() {
  const query  = document.getElementById('audit-search').value.trim().toLowerCase();
  const type   = document.getElementById('audit-filter-type').value;
  const rows   = document.querySelectorAll('#audit-body tr');
  let visible  = 0;

  rows.forEach(row => {
    const text     = row.textContent.toLowerCase();
    const rowType  = row.dataset.type;
    const matchQ   = !query || text.includes(query);
    const matchT   = !type  || rowType === type;

    if (matchQ && matchT) {
      row.classList.remove('hidden-log');
      visible++;
    } else {
      row.classList.add('hidden-log');
    }
  });

  document.getElementById('audit-no-results').classList.toggle('hidden', visible > 0);
}

// --- Profile page ---
function enableEdit() {
  ['profile-username', 'profile-email'].forEach(id => {
    document.getElementById(id).disabled = false;
  });
  document.getElementById('edit-btn').classList.add('hidden');
  document.getElementById('save-btn').classList.remove('hidden');
  document.getElementById('cancel-btn').classList.remove('hidden');
}

function cancelEdit() {
  ['profile-username', 'profile-email'].forEach(id => {
    document.getElementById(id).disabled = true;
  });
  document.getElementById('edit-btn').classList.remove('hidden');
  document.getElementById('save-btn').classList.add('hidden');
  document.getElementById('cancel-btn').classList.add('hidden');
  hideMessage('profile-message');
}

function saveProfile() {
  const username = document.getElementById('profile-username').value.trim();
  const email    = document.getElementById('profile-email').value.trim();
  if (!username || !email) {
    showMessage('profile-message', 'Username and email cannot be empty.', 'error');
    return;
  }
  cancelEdit();
  showMessage('profile-message', 'Profile updated! (backend not connected yet)', 'success');
}

function changePassword() {
  const current = document.getElementById('current-pass').value;
  const newPass = document.getElementById('new-pass').value;
  const confirm = document.getElementById('confirm-pass').value;

  if (!current || !newPass || !confirm) {
    showMessage('password-message', 'Please fill in all fields.', 'error');
    return;
  }
  if (newPass.length < 8) {
    showMessage('password-message', 'New password must be at least 8 characters.', 'error');
    return;
  }
  if (newPass !== confirm) {
    showMessage('password-message', 'Passwords do not match.', 'error');
    return;
  }
  showMessage('password-message', 'Password updated! (backend not connected yet)', 'success');
}

// --- Chat page: switch main area to DM conversation ---
function switchToDM(name, status, el) {
  // Update header
  document.getElementById('chat-title').textContent = '@ ' + name;
  document.getElementById('chat-desc').textContent  = status === 'online' ? 'Online' : 'Offline';
  document.getElementById('chat-desc').style.color  = status === 'online' ? 'var(--success)' : 'var(--text-3)';

  // Update input placeholder
  document.getElementById('chat-input-field').placeholder = 'Message ' + name;

  // Replace messages with DM conversation
  document.getElementById('chat-messages-area').innerHTML = `
    <div class="message">
      <div class="message-avatar">${name.charAt(0).toUpperCase()}</div>
      <div class="message-body">
        <span class="message-author">${name}</span>
        <span class="message-time">Today at 9:10 AM</span>
        <p class="message-text">Hey, how's it going?</p>
      </div>
    </div>
    <div class="message">
      <div class="message-avatar">U</div>
      <div class="message-body">
        <span class="message-author">User</span>
        <span class="message-time">Today at 9:12 AM</span>
        <p class="message-text">Pretty good! Working on the frontend.</p>
      </div>
    </div>
  `;

  // Highlight selected DM item
  document.querySelectorAll('.dm-item').forEach(i => i.classList.remove('dm-item-active'));
  if (el) el.classList.add('dm-item-active');
}

// --- Groups page ---
function selectGroup(item) {
  document.querySelectorAll('.group-list-item').forEach(el => el.classList.remove('selected'));
  item.classList.add('selected');
  document.getElementById('enter-btn').classList.remove('hidden');
}

function showPanel(name) {
  document.getElementById(name + '-panel').classList.remove('hidden');
}

function hidePanel(name) {
  document.getElementById(name + '-panel').classList.add('hidden');
}

function handleJoin() {
  const code = document.getElementById('invite-code').value.trim();
  if (!code) {
    showMessage('join-message', 'Please enter an invite code.', 'error');
    return;
  }
  showMessage('join-message', 'Join request sent! (backend not connected yet)', 'success');
}

function handleCreate() {
  const name = document.getElementById('new-group-name').value.trim();
  if (!name) {
    showMessage('create-message', 'Please enter a group name.', 'error');
    return;
  }
  showMessage('create-message', 'Group created! (backend not connected yet)', 'success');
}

// --- Chat page: member info card ---
function showMemberCard(item) {
  const name   = item.dataset.name;
  const role   = item.dataset.role;
  const joined = item.dataset.joined;
  const status = item.dataset.status;

  document.getElementById('mc-avatar').textContent  = name.charAt(0).toUpperCase();
  document.getElementById('mc-name').textContent    = name;
  document.getElementById('mc-status').textContent  = status;
  document.getElementById('mc-role').textContent    = role;
  document.getElementById('mc-joined').textContent  = joined;

  document.getElementById('mc-status').style.color =
    status === 'Online' ? 'var(--success)' : 'var(--text-3)';

  document.getElementById('member-card').classList.remove('hidden');
}

function closeMemberCard() {
  document.getElementById('member-card').classList.add('hidden');
}

function goToDM() {
  closeMemberCard();

  // close members panel
  document.getElementById('members-panel').classList.add('hidden');

  // scroll DM section into view and highlight it
  const dmSection = document.querySelector('.dm-section');
  if (dmSection) {
    dmSection.scrollIntoView({ behavior: 'smooth' });
    dmSection.classList.add('dm-highlight');
    setTimeout(() => dmSection.classList.remove('dm-highlight'), 1500);
  }
}

// --- Chat page: toggle members panel ---
function toggleMembers() {
  document.getElementById('members-panel').classList.toggle('hidden');
}

// --- Chat page: search messages within group ---
function searchMessages(query) {
  const messages = document.querySelectorAll('.chat-messages .message');
  const q = query.trim().toLowerCase();
  let visibleCount = 0;

  messages.forEach(msg => {
    const text = msg.querySelector('.message-text').textContent.toLowerCase();
    const author = msg.querySelector('.message-author').textContent.toLowerCase();
    if (!q || text.includes(q) || author.includes(q)) {
      msg.classList.remove('hidden-by-search');
      visibleCount++;
    } else {
      msg.classList.add('hidden-by-search');
    }
  });

  let noResults = document.getElementById('search-no-results');
  if (q && visibleCount === 0) {
    if (!noResults) {
      noResults = document.createElement('p');
      noResults.id = 'search-no-results';
      noResults.className = 'search-no-results';
      noResults.textContent = 'No messages found.';
      document.querySelector('.chat-messages').appendChild(noResults);
    }
  } else if (noResults) {
    noResults.remove();
  }
}

// --- Chat page: toggle group accordion ---
function toggleGroup(btn) {
  const section = btn.closest('.group-section');
  const arrow   = btn.querySelector('.toggle-arrow');
  const isOpen  = section.classList.toggle('open');
  arrow.innerHTML = isOpen ? '&#9660;' : '&#9654;';
}