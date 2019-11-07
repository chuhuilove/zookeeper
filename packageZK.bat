rd /S /Q zookeeperCopy

mkdir zookeeperCopy

xcopy zookeeper zookeeperCopy /E

cd zookeeperCopy 

RD /S /Q .eclipse
RD /S /Q .git
RD /S /Q .setting
RD /S /Q .revision
RD /S /Q build
RD /S /Q zkdata
RD /S /Q .idea



cd ../

del /f /a /q zookeeper.rar

rar a  zookeeper.rar zookeeperCopy 