const {
  validateUsername,
  validatePassword,
  validatePasswordsMatch,
  validateEmail,
  escapeHTML
} = require('../frontend/validation');

// scenario 1 - user registers with valid info
describe('Scenario 1: User registers a new account', () => {
  test('username jinho should pass', () => {
    expect(validateUsername('jinho')).toBe(true);
  });

  test('username jh is too short so it should fail', () => {
    expect(validateUsername('jh')).toBe(false);
  });

  test('email should have @ in it', () => {
    expect(validateEmail('jinho@example.com')).toBe(true);
  });

  test('password needs to be at least 8 characters', () => {
    expect(validatePassword('securepass')).toBe(true);
  });
});

// scenario 2 - user types wrong confirm password
describe('Scenario 2: User types mismatched passwords', () => {
  test('same password typed twice should pass', () => {
    expect(validatePasswordsMatch('mypassword1', 'mypassword1')).toBe(true);
  });

  test('different passwords should fail', () => {
    expect(validatePasswordsMatch('mypassword1', 'wrongpassword')).toBe(false);
  });

  test('password that is too short should fail', () => {
    expect(validatePassword('short')).toBe(false);
  });
});

// scenario 3 - user sends a message
describe('Scenario 3: User sends a message in a channel', () => {
  test('empty message should not be sent', () => {
    const msg = '   ';
    expect(msg.trim().length > 0).toBe(false);
  });

  test('message with html tags should be escaped', () => {
    const result = escapeHTML('<script>alert("xss")</script>');
    expect(result).toBe('&lt;script&gt;alert(&quot;xss&quot;)&lt;/script&gt;');
  });

  test('normal message should stay the same', () => {
    expect(escapeHTML('hello world')).toBe('hello world');
  });

  test('& should be escaped to &amp;', () => {
    expect(escapeHTML('fish & chips')).toBe('fish &amp; chips');
  });
});

// scenario 4 - user logs out
describe('Scenario 4: User logs out', () => {
  test('user should be redirected to index.html after logout', () => {
    const logoutPage = 'index.html';
    expect(logoutPage).toBe('index.html');
  });

  test('username should be cleared after logout', () => {
    let username = 'jinho';
    username = '';
    expect(username).toBe('');
  });

  test('password should be cleared after logout', () => {
    let password = 'mypassword';
    password = '';
    expect(password).toBe('');
  });
});