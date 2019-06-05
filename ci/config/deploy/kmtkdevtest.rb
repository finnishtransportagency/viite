role :app, %w{web@172.17.204.35}
role :web, %w{web@172.17.204.35}
server '172.17.204.35', user: 'web', roles: %w{web app}