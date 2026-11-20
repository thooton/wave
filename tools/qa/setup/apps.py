"""The six apps the setup-file test covers: ids, what the screens say, and the fake values for the generator page.

Shared by generate.py, build_copies.py and import_setup.py; tools/qa/test_setup_import.py checks it against the site's
descriptors, so a changed app id or setting fails on the host before it reaches the phone.
"""
FAKE = 'QAfake' + 'x' * 30   # 36 characters from every token alphabet the descriptors allow

# app: owner package (never touched), launcher activity, name on the setup screen, backend, fake settings or delta values
APPS = {
    'places': {'package': 'com.places.xp3', 'activity': 'com.places.xp3.MainActivity', 'name': 'Places', 'managed': False,
               'values': {'key': 'QAfakeKey_' + 'x' * 30}},
    'canvas': {'package': 'com.todo.xp3', 'activity': 'com.canvas.xp3.MainActivity', 'name': 'Canvas', 'managed': False,
               'values': {'baseUrl': 'https://school.example.edu', 'accessToken': FAKE}},
    'charge': {'package': 'com.teslacharge.xp3', 'activity': 'com.teslacharge.xp3.MainActivity', 'name': 'Charge',
               'managed': False, 'values': {'refreshToken': FAKE}},
    'mail': {'package': 'com.mail.xp3', 'activity': 'com.mail.xp3.MainActivity', 'name': 'Mail', 'managed': False,
             'values': {'email': 'qa@example.org', 'clientId': '123456789012-qafake.apps.googleusercontent.com',
                        'clientSecret': 'GOCSPX-qafakefake', 'refreshToken': '1//0qafake' + 'x' * 30}},
    'authenticator': {'package': 'com.authenticator.xp3', 'activity': 'com.authenticator.xp3.MainActivity',
                      'name': 'Authenticator', 'managed': True, 'values': {'server': 'https://relay.example.org', 'token': FAKE}},
    'flashcards': {'package': 'com.flashcards.xp3', 'activity': 'com.flashcards.xp3.MainActivity', 'name': 'Flashcards',
                   'managed': True, 'values': {'server': 'https://relay.example.org', 'token': FAKE}},
}
COPY_SUFFIX = '.wavetest'
SERIAL = '8516067e'
# Substrings that must never appear on a screen after an import.
SECRET_MARKERS = ('QAfake', 'GOCSPX-qafake', '1//0qafake')
