const API = '/api';
async function fetchEmployees() {
  const res = await fetch(API + '/employees');
  const data = await res.json();
  const tbody = document.querySelector('#empTable tbody');
  tbody.innerHTML = '';
  data.forEach(e => {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${e.id}</td><td>${e.name}</td><td>${e.department}</td><td>${e.joiningDate}</td><td><button data-att="${e.id}">Mark Today</button> <button data-view-att="${e.id}">View</button></td><td><button data-id="${e.id}">Delete</button></td>`;
    tbody.appendChild(tr);
  });
  document.querySelectorAll('button[data-id]').forEach(btn => {
    btn.onclick = async () => {
      const id = btn.getAttribute('data-id');
      if (!confirm('Delete employee #' + id + '?')) return;
      const r = await fetch(API + '/employees?id=' + id, { method: 'DELETE' });
      if (r.ok) fetchEmployees(), fetchReport();
      else alert('Delete failed');
    };
  });
  document.querySelectorAll('button[data-att]').forEach(btn => {
    btn.onclick = async () => {
      const id = btn.getAttribute('data-att');
      const today = new Date();
      const dd = String(today.getDate()).padStart(2,'0');
      const mm = String(today.getMonth()+1).padStart(2,'0');
      const yyyy = today.getFullYear();
      const dateStr = dd + '-' + mm + '-' + yyyy;
      const r = await fetch(API + '/attendance', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ employeeId: id, date: dateStr }) });
      if (r.ok) { alert('Attendance recorded'); fetchReport(); }
      else { alert('Attendance failed'); }
    };
  });
  document.querySelectorAll('button[data-view-att]').forEach(btn => {
    btn.onclick = async () => {
      const id = btn.getAttribute('data-view-att');
      const r = await fetch(API + '/attendance?employeeId=' + id);
      const data = await r.json();
      if (Array.isArray(data)) {
        alert('Attendance for #' + id + ':\n' + data.map(a => a.date).join('\n'));
      } else alert('No attendance');
    };
  });
}

document.getElementById('addForm').onsubmit = async (e) => {
  e.preventDefault();
  const name = document.getElementById('name').value.trim();
  const department = document.getElementById('department').value.trim();
  const joiningDate = document.getElementById('joiningDate').value.trim();
  const payload = { name, department, joiningDate };
  const res = await fetch(API + '/employees', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(payload) });
  if (res.status === 201) {
    document.getElementById('addForm').reset();
    fetchEmployees(); fetchReport();
  } else {
    const txt = await res.text(); alert('Add failed: ' + txt);
  }
};

async function fetchReport() {
  const res = await fetch(API + '/report');
  const data = await res.json();
  document.getElementById('report').textContent = JSON.stringify(data, null, 2);
}

// init
fetchEmployees(); fetchReport();
