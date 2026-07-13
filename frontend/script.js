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

// --- Read URL params (index.html only) ---
function readURLParams() {
  if (!document.getElementById('login-form')) return;

  const params  = new URLSearchParams(window.location.search);
  const tab     = params.get('tab');
  const error   = params.get('error');
  const success = params.get('success');

  if (tab === 'register') {
    switchTab('register');
  } else {
    switchTab('login');
  }

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

  const isAdmin  = item.dataset.admin === 'true';
  const code     = item.dataset.code;
  const name     = item.querySelector('.group-card-name').textContent;
  const panel    = document.getElementById('invite-code-panel');

  if (isAdmin) {
    document.getElementById('invite-code-value').textContent = code;
    document.getElementById('invite-group-name').textContent = name;
    hideMessage('copy-message');
    panel.classList.remove('hidden');
  } else {
    panel.classList.add('hidden');
  }
}

function copyInviteCode() {
  const code = document.getElementById('invite-code-value').textContent;
  navigator.clipboard.writeText(code).then(() => {
    showMessage('copy-message', 'Invite code copied to clipboard!', 'success');
  }).catch(() => {
    showMessage('copy-message', 'Could not copy. Please copy manually.', 'error');
  });
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

  // Generate a placeholder invite code
  const code = 'AEG-' + Math.random().toString(36).substring(2, 6).toUpperCase() +
               '-' + Math.random().toString(36).substring(2, 6).toUpperCase();

  hidePanel('create');
  document.getElementById('new-group-name').value = '';
  document.getElementById('new-group-desc').value = '';

  document.getElementById('modal-group-name').textContent = name;
  document.getElementById('modal-code-value').textContent = code;
  hideMessage('modal-copy-message');
  document.getElementById('invite-modal-overlay').classList.remove('hidden');
}

function closeInviteModal() {
  document.getElementById('invite-modal-overlay').classList.add('hidden');
}

function copyModalCode() {
  const code = document.getElementById('modal-code-value').textContent;
  navigator.clipboard.writeText(code).then(() => {
    showMessage('modal-copy-message', 'Invite code copied!', 'success');
  }).catch(() => {
    showMessage('modal-copy-message', 'Could not copy. Please copy manually.', 'error');
  });
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

// --- Chat page: attach file modal ---
function openAttachModal() {
  document.getElementById('attach-modal-overlay').classList.remove('hidden');
}

function closeAttachModal() {
  document.getElementById('attach-modal-overlay').classList.add('hidden');
  clearAttach();
}

function handleFileSelect(input) {
  if (!input.files || !input.files[0]) return;
  const file = input.files[0];
  const size = file.size < 1024 * 1024
    ? (file.size / 1024).toFixed(1) + ' KB'
    : (file.size / (1024 * 1024)).toFixed(1) + ' MB';
  document.getElementById('attach-file-name').textContent = file.name + ' (' + size + ')';
  document.getElementById('attach-preview').classList.remove('hidden');
  document.getElementById('attach-send-btn').disabled = false;
}

function clearAttach() {
  document.getElementById('file-input').value = '';
  document.getElementById('attach-preview').classList.add('hidden');
  document.getElementById('attach-send-btn').disabled = true;
}

function sendAttach() {
  const fileName = document.getElementById('attach-file-name').textContent;
  closeAttachModal();
  const area = document.getElementById('chat-messages-area');
  const msg = document.createElement('div');
  msg.className = 'message';
  msg.innerHTML = `
    <div class="message-avatar">U</div>
    <div class="message-body">
      <span class="message-author">User</span>
      <span class="message-time">Just now</span>
      <div class="message-file">&#128196; ${fileName}</div>
    </div>
  `;
  area.appendChild(msg);
  area.scrollTop = area.scrollHeight;
}

// --- Chat page: toggle group accordion ---
function toggleGroup(btn) {
  const section = btn.closest('.group-section');
  const arrow   = btn.querySelector('.toggle-arrow');
  const isOpen  = section.classList.toggle('open');
  arrow.innerHTML = isOpen ? '&#9660;' : '&#9654;';
}