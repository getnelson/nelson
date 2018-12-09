data_dir  = "/var/lib/nomad"

server {
  enabled          = true
  bootstrap_expect = 3
}

client {
  enabled       = true
  network_speed = 10
  // network_interface = "lo"

  options {
    "docker.endpoint" = "unix:///var/run/docker.sock"
  }
}

consul {
  checks_use_advertise = "true"
}
