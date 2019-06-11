role :app, %w{web@kmtkdevtest}
role :web, %w{web@kmtkdevtest}
server 'kmtkdevtest', user: 'web', roles: %w{web app}