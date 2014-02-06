# -*- mode: ruby -*-
# vi: set ft=ruby :

# Vagrantfile API/syntax version. Don't touch unless you know what you're doing!
VAGRANTFILE_API_VERSION = "2"

$script = <<SCRIPT
apt-get update
apt-get upgrade
apt-get -y install python-software-properties
add-apt-repository -y ppa:webupd8team/java
echo debconf shared/accepted-oracle-license-v1-1 select true | debconf-set-selections
echo debconf shared/accepted-oracle-license-v1-1 seen true | debconf-set-selections
apt-get update
apt-get -y install oracle-java7-installer
/vagrant/sbt clean
SCRIPT

Vagrant.configure(VAGRANTFILE_API_VERSION) do |config|
  config.vm.box = "precise64"
  config.vm.box_url = "http://files.vagrantup.com/precise64.box"
  config.vm.provision :shell, :inline => $script
  config.vm.provider "virtualbox" do |v|
    v.memory = 1024
  end
  config.vm.network "forwarded_port", guest: 8080, host: 9992
end

