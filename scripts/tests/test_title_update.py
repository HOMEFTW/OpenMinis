"""Exercise the production Room update query against SQLite, including rename races."""
import re
import sqlite3
import unittest
from pathlib import Path


class TitleUpdateTest(unittest.TestCase):
    def setUp(self):
        source = (Path(__file__).resolve().parents[2] /
                  'src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt').read_text(encoding='utf-8')
        self.query = re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun updateSessionTitleIfUnchanged', source).group(1)
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT, category TEXT, updated_at INTEGER)')

    def update(self, expected, title='AI title', category=None):
        return self.db.execute(self.query, dict(id='s', expectedTitle=expected, title=title,
                                               category=category, updatedAt=9)).rowcount

    def test_manual_rename_between_read_and_write_wins(self):
        self.db.execute("INSERT INTO sessions VALUES ('s', 'New Chat', 'chat', 1)")
        observed = self.db.execute('SELECT title FROM sessions').fetchone()[0]
        self.db.execute("UPDATE sessions SET title='My title', updated_at=2")
        self.assertEqual(0, self.update(observed, category='code'))
        self.assertEqual(('My title', 'chat', 2), self.db.execute('SELECT title,category,updated_at FROM sessions').fetchone())

    def test_null_empty_and_default_titles_can_be_set_once(self):
        for old in (None, '', 'New Chat'):
            with self.subTest(old=old):
                self.db.execute('DELETE FROM sessions')
                self.db.execute("INSERT INTO sessions VALUES ('s', ?, 'chat', 1)", (old,))
                self.assertEqual(1, self.update(old))
                self.assertEqual(0, self.update(old, title='Other generated title'))
                self.assertEqual(('AI title', 'chat'), self.db.execute('SELECT title,category FROM sessions').fetchone())

    def test_deleted_session_is_not_recreated(self):
        self.assertEqual(0, self.update(None))
        self.assertEqual(0, self.db.execute('SELECT count(*) FROM sessions').fetchone()[0])


if __name__ == '__main__':
    unittest.main()
