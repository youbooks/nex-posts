## JMM

![mage-20180404123223](/var/folders/hr/c1f08q_n0msf4gp05cdnc04c0000gn/T/abnerworks.Typora/image-201804041232235.png)

#### Java堆

　　Java 堆是被所有线程共享的一块内存区域，在虚拟机启动时创建。这个区域是用来存放对象实例的，几乎所有对象实例都会在这里分配内存。堆是Java垃圾收集器管理的主要区域（GC堆），垃圾收集器实现了对象的自动销毁。Java堆可以细分为：新生代和老年代；再细致一点的有Eden空间，From Survivor空间，To Survivor空间等。Java堆可以处于物理上不连续的内存空间中，只要逻辑上是连续的即可，就像我们的磁盘空间一样。可以通过-Xmx和-Xms控制

#### 方法区

方法区和Java堆一样，是所有线程共享的内存区域，用于存放已被虚拟机加载的类信息、常量、静态变量和即时编译器编译后的代码等数据。
运行时常量池是方法区的一部分，用于存放编译期间生成的各种字面常量和符号引用。

#### 本地方法栈

　　任何本地方法接口都会使用某种本地方法栈。当线程调用Java方法时，虚拟机会创建一个新的栈帧并压入Java栈。然而当它调用的是本地方法时，虚拟机会保持Java栈不变，不再在线程的Java栈中压入新的帧，虚拟机只是简单地动态连接并直接调用指定的本地方法。

　　如果某个虚拟机实现的本地方法接口是使用C连接模型的话，那么它的本地方法栈就是C栈。当C程序调用一个C函数时，其栈操作都是确定的。传递给该函数的参数以某个确定的顺序压入栈，它的返回值也以确定的方式传回调用者。同样，这就是虚拟机实现中本地方法栈的行为。
　　
　　举例:
　　![](https://images2015.cnblogs.com/blog/990532/201608/990532-20160827203431726-2050515871.png)
　　
　　该线程首先调用了两个Java方法，而第二个Java方法又调用了一个本地方法，这样导致虚拟机使用了一个本地方法栈。假设这是一个C语言栈，其间有两个C函数，第一个C函数被第二个Java方法当做本地方法调用，而这个C函数又调用了第二个C函数。之后第二个C函数又通过本地方法接口回调了一个Java方法（第三个Java方法），最终这个Java方法又调用了一个Java方法（它成为图中的当前方法）。



## 虚拟机栈


当有一个方法被调用时，代表这个方法的栈帧入栈；当这个方法返回时，其栈帧出栈。因此，虚拟机栈中栈帧的入栈顺序就是方法调用顺序。

构成:

- 局部变量表
- 操作数栈
- 动态连接
- 返回地址

![mage-20180404142750](/var/folders/hr/c1f08q_n0msf4gp05cdnc04c0000gn/T/abnerworks.Typora/image-201804041427503.png)



Java 程序编译之后就变成了一条条字节码指令，其形式类似汇编，但和汇编有不同之处：汇编指令的操作数存放在数据段和寄存器中，可通过存储器或寄存器寻址找到需要的操作数

而 Java 字节码指令的操作数存放在操作数栈中，当执行某条带 n 个操作数的指令时，就从栈顶取 n 个操作数，然后把指令的计算结果（如果有的话）入栈。

#### 举例

比如计算 1 + 2，在汇编指令是这样的：

```
mov ax, 1 ;把 1 放入寄存器 ax
add ax, 2 ;用 ax 的内容和 2 相加后存入 ax
```

而 JVM 的字节码指令是这样的：

```
iconst_1 //把整数 1 压入操作数栈
iconst_2 //把整数 2 压入操作数栈
iadd //栈顶的两个数相加后出栈，结果入栈
```



由于操作数栈是内存空间，所以字节码指令不必担心不同机器上寄存器以及机器指令的差别，从而做到了平台无关。

局部变量表中的变量不可直接使用，如需使用必须通过相关指令将其加载至操作数栈中作为操作数使用。比如有一个方法 void foo()，其中的代码为：int a = 1 + 2; int b = a + 3;，编译为字节码指令就是这样的：

```
iconst_1 //把整数 1 压入操作数栈
iconst_2 //把整数 2 压入操作数栈
iadd //栈顶的两个数出栈后相加，结果入栈；实际上前三步会被编译器优化为：iconst_3
istore_1 //把栈顶的内容放入局部变量表中索引为 1 的 slot 中，也就是 a 对应的空间中
iload_1 // 把局部变量表索引为 1 的 slot 中存放的变量值（3）加载至操作数栈
iconst_3 
iadd //栈顶的两个数出栈后相加，结果入栈
istore_2 // 把栈顶的内容放入局部变量表中索引为 2 的 slot 中，也就是 b 对应的空间中
return // 方法返回指令，回到调用点
```

#### 什么是 slot

slot 是局部变量表中的空间单位，虚拟机规范中有规定，对于 32 位之内的数据，用一个 slot 来存放，如 int，short，float 等；对于 64 位的数据用连续的两个 slot 来存放，如 long，double 等。引用类型的变量 JVM 并没有规定其长度，它可能是 32 位，也有可能是 64 位的，所以既有可能占一个 slot，也有可能占两个 slot。

#### JVM 字节码指令

Java 的指令以字节为单位，也就是一个字节代表一条指令。

比如 iconst_1 就是一条指令，它占一个字节，那么自然 Java 指令不会超过 256 条。

指令的操作数分两种：一种是嵌入在指令中的，通常是指令字节后面的若干个字节；另一种是存放在操作数栈中的。为了区别，我们把前者叫做嵌入式操作数，把后者叫做栈内操作数。



###**动态连接:**

　　在说明什么是动态连接之前先看看方法的大概调用过程,首先在虚拟机运行的时候,运行时常量池会保存大量的符号引用,这些符号引用可以看成是每个方法的间接引用,如果代表栈帧A的方法想调用代表栈帧B的方法,那么这个虚拟机的方法调用指令就会以B方法的符号引用作为参数,但是因为符号引用并不是直接指向代表B方法的内存位置,所以在调用之前还必须要将符号引用转换为直接引用,然后通过直接引用才可以访问到真正的方法,这时候就有一点需要注意,如果符号引用是在**类加载阶段或者第一次使用的时候转化为直接应用**,那么这种转换成为**静态解析**,如果是在**运行期间转换为直接引用**,那么这种转换就成为**动态连接。**



#### 举例:

```java
public class X {
  public void foo() {
    bar();
  }

  public void bar() { }
}
```

```class
Classfile /private/tmp/X.class
  Last modified Jun 13, 2015; size 372 bytes
  MD5 checksum 8abb9cbb66266e8bc3f5eeb35c3cc4dd
  Compiled from "X.java"
public class X
  SourceFile: "X.java"
  minor version: 0
  major version: 51
  flags: ACC_PUBLIC, ACC_SUPER
Constant pool:
   #1 = Methodref          #4.#16         //  java/lang/Object."<init>":()V
   #2 = Methodref          #3.#17         //  X.bar:()V
   #3 = Class              #18            //  X
   #4 = Class              #19            //  java/lang/Object
   #5 = Utf8               <init>
   #6 = Utf8               ()V
   #7 = Utf8               Code
   #8 = Utf8               LineNumberTable
   #9 = Utf8               LocalVariableTable
  #10 = Utf8               this
  #11 = Utf8               LX;
  #12 = Utf8               foo
  #13 = Utf8               bar
  #14 = Utf8               SourceFile
  #15 = Utf8               X.java
  #16 = NameAndType        #5:#6          //  "<init>":()V
  #17 = NameAndType        #13:#6         //  bar:()V
  #18 = Utf8               X
  #19 = Utf8               java/lang/Object
{
  public X();
    flags: ACC_PUBLIC
    Code:
      stack=1, locals=1, args_size=1
         0: aload_0       
         1: invokespecial #1                  // Method java/lang/Object."<init>":()V
         4: return        
      LineNumberTable:
        line 1: 0
      LocalVariableTable:
        Start  Length  Slot  Name   Signature
               0       5     0  this   LX;

  public void foo();
    flags: ACC_PUBLIC
    Code:
      stack=1, locals=1, args_size=1
         0: aload_0       
         1: invokevirtual #2                  // Method bar:()V
         4: return        
      LineNumberTable:
        line 3: 0
        line 4: 4
      LocalVariableTable:
        Start  Length  Slot  Name   Signature
               0       5     0  this   LX;

  public void bar();
    flags: ACC_PUBLIC
    Code:
      stack=0, locals=1, args_size=1
         0: return        
      LineNumberTable:
        line 6: 0
      LocalVariableTable:
        Start  Length  Slot  Name   Signature
               0       1     0  this   LX;
}
```

foo()方法里的一条字节码指令：

```
1: invokevirtual #2  // Method bar:()V
```

这在Class文件中的实际编码为：

```
[B6] [00 02]
```

其中0xB6是[invokevirtual指令](https://link.zhihu.com/?target=https%3A//docs.oracle.com/javase/specs/jvms/se7/html/jvms-6.html%23jvms-6.5.invokevirtual)的操作码（opcode），后面的0x0002是该指令的操作数（operand），用于指定要调用的目标方法。

去找下标为2的常量池项，是：

```
#2 = Methodref          #3.#17         //  X.bar:()V
```

class文件中实际编码为:

```
[0A] [00 03] [00 11]
```

其中0x0A是CONSTANT_Methodref_info的tag，后面的0x0003和0x0011是该常量池项的两个部分：class_index和name_and_type_index。这两部分分别都是常量池下标，引用着另外两个常量池项。

```
   #2 = Methodref          #3.#17         //  X.bar:()V
   #3 = Class              #18            //  X
  #18 = Utf8               X
  #17 = NameAndType        #13:#6         //  bar:()V
  #13 = Utf8               bar
   #6 = Utf8               ()V
   
   把引用关系画成一棵树的话：

     #2 Methodref X.bar:()V
     /                     \
#3 Class X       #17 NameAndType bar:()V
    |                /             \
#18 Utf8 X    #13 Utf8 bar     #6 Utf8 ()V
```



假定我们要第一次执行到foo()方法里调用bar()方法的那条invokevirtual指令了。
此时JVM会发现该指令尚未被解析（resolve），所以会先去解析一下。
通过其操作数所记录的常量池下标0x0002，找到常量池项#2，发现该常量池项也尚未被解析（resolve），于是进一步去解析一下。
通过Methodref所记录的class_index找到类名，进一步找到被调用方法的类的ClassClass结构体；然后通过name_and_type_index找到方法名和方法描述符，到ClassClass结构体上记录的方法列表里找到匹配的那个methodblock；最终把找到的methodblock的指针写回到常量池项#2里。

```
[00 03] [00 11]
变为
[00 23 76 45]
```

符号引用通常是设计字符串的——用文本形式来表示引用关系。而直接引用是JVM（或其它运行时环境）所能直接使用的形式。

它既可以表现为直接指针（如上面常量池项#2解析为methodblock*）。关键点不在于形式是否为“直接指针”，而是在于JVM是否能“直接使用”这种形式的数据。



### **方法的返回地址**

　　方法的返回分为两种情况,一种是正常退出,退出后会根据方法的定义来决定是否要传返回值给上层的调用者,一种是异常导致的方法结束,这种情况是不会传返回值给上层的调用方法.

　　不过无论是那种方式的方法结束,在退出当前方法时都会跳转到当前方法被调用的位置,如果方法是正常退出的,则调用者的PC计数器的值就可以作为返回地址,如果是因为异常退出的,则是需要通过异常处理表来确定.

　　在方法的的一次调用就对应着栈帧在虚拟机栈中的一次入栈出栈操作,因此方法退出时可能做的事情包括,恢复上层方法的局部变量表以及操作数栈,如果有返回值的话,就把返回值压入到调用者栈帧的操作数栈中,还会把PC计数器的值调整为方法调用入口的下一条指令。













