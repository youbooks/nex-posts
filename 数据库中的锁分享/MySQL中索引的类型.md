###普通索引
这是最基本的索引类型，而且它没有唯一性之类的限制。

###唯一性索引
这种索引和前面的“普通索引”基本相同，但有一个区别：索引列的所有值都只能出现一次，即必须唯一。唯一性索引可以用以下几种方式创建：

如果能确定某个数据列将只包含彼此各不相同的值，在为这个数据列创建索引的时候就应该用关键字UNIQUE把它定义为一个唯一索引。这么做的好处：一是简化了MySQL对这个索引的管理工作，这个索引也因此而变得更有效率；二是MySQL会在有新记录插入数据表时，自动检查新记录的这个字段的值是否已经在某个记录的这个字段里出现过了；如果是，MySQL将拒绝插入那条新记录。也就是说，唯一索引可以保证数据记录的唯一性。事实上，在许多场合，人们创建唯一索引的目的往往不是为了提高访问速度，而只是为了避免数据出现重复

###主键
主键是一种唯一性索引，但它必须指定为“[PRIMARYKEY](http://whatis.ctocio.com.cn/searchwhatis/287/6026287.shtml)”。如果你曾经用过AUTO_[INCREMENT](http://whatis.ctocio.com.cn/searchwhatis/487/6025487.shtml)类型的列，你可能已经熟悉主键之类的概念了。主键一般在创建表的时候指定，例如“CREATETABLE tablename ( [...], PRIMARY [KEY](http://whatis.ctocio.com.cn/searchwhatis/25/5948525.shtml) (列的列表) );”。但是，我们也可以通过修改表的方式加入主键，例如“ALTER TABLE tablename ADD PRIMARY KEY(列的列表); ”。每个表只能有一个主键。

###全文索引
MySQL从3.23.23版开始支持全文索引和全文检索。在MySQL中，全文索引的索引类型为FULLTEXT。全文索引可以在VARCHAR或者[TEXT](http://whatis.ctocio.com.cn/searchwhatis/162/6092662.shtml)类型的列上创建。它可以通过CREATETABLE命令创建，也可以通过ALTER TABLE或CREATE INDEX命令创建。对于大规模的数据集，通过ALTERTABLE（或者CREATEINDEX）命令创建全文索引要比把记录插入带有全文索引的空表更快。本文下面的讨论不再涉及全文索引，要了解更多信息，请参见MySQLdocumentation。 