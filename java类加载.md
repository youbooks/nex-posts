## 类加载过程

加载，连接(验证，准备，解析)，初始化，使用，卸载。

1. 加载
   加载主要是将.class文件（也可以是zip包）通过二进制字节流读入到JVM中。 在加载阶段，JVM需要完成3件事：
    1）通过classloader在classpath中获取XXX.class文件，将其以二进制流的形式读入内存。
    2）将字节流所代表的静态存储结构转化为方法区的运行时数据结构；
    3）在内存中生成一个该类的java.lang.Class对象，作为方法区这个类的各种数据的访问入口。

2. 验证
    主要确保加载进来的字节流符合JVM规范。验证阶段会完成以下4个阶段的检验动作：
    1）文件格式验证
    2）元数据验证(是否符合Java语言规范)
    3）字节码验证（确定程序语义合法，符合逻辑）
    4）符号引用验证（确保下一步的解析能正常执行）
    2.2. 准备
    准备是连接阶段的第二步，主要为静态变量在方法区分配内存，并设置默认初始值。
    2.3. 解析
    解析是连接阶段的第三步，是虚拟机将常量池内的符号引用替换为直接引用的过程。

3. 初始化

   初始化这个阶段就是将静态变量（类变量）赋值的过程，即只有static修饰的才能被初始化，执行的顺序就是：父类静态域或着静态代码块，然后是子类静态域或者子类静态代码块（静态代码块先被加载，然后再是静态属性）

## 两个例子

### 例子A

```java
package com.nex.test;
class A{
    static int a;//类变量
    String name;
    int id;
    //静态代码块
    static{
        a=10;
        System.out.println("这是父类的静态代码块"+a);
    }
    //构造代码块
    {
        id=11;
        System.out.println("这是父类的构造代码块id:"+id);
    }
    A(){
        System.out.println("这是父类的无参构造函数");
    }
    A(String name){
        System.out.println("这是父类的name"+name);
    }
}
class B extends A{
    String name;
    static int b;
    static{
        b=12;
        System.out.println("这是子类的静态代码块"+b);
    }
     B(String name) {
        super();
        this.name = name;
        System.out.println("这是子类的name:"+name);
    }
}
public class Test {
public static void main(String[] args) {
    B bb=new B("GG");
}
}
```

![](https://ww1.sinaimg.cn/large/007i4MEmgy1g0aswsiwk2j30fe086gmo.jpg)



### 例子B

```java
 public class ClassloadSort1 {

        public static void main(String[] args) {
            Singleton.getInstance();
            System.out.println("Singleton value1:" + Singleton.value1);
            System.out.println("Singleton value2:" + Singleton.value2);
    
            Singleton2.getInstance2();
            System.out.println("Singleton2 value1:" + Singleton2.value1);
            System.out.println("Singleton2 value2:" + Singleton2.value2);
        }
    }
    
    class Singleton {
        static {
            System.out.println(Singleton.value1 + "\t" + Singleton.value2 + "\t" + Singleton.singleton);
            //System.out.println(Singleton.value1 + "\t" + Singleton.value2);
        }
        private static Singleton singleton = new Singleton();
        public static int value1 = 5;
        public static int value2 = 3;
    
        private Singleton() {
            value1++;
            value2++;
        }

        public static Singleton getInstance() {
            return singleton;
        }

        int count = 10;

        {
            System.out.println("count = " + count);
        }
    }
    
    class Singleton2 {
        static {
            System.out.println(Singleton2.value1 + "\t" + Singleton2.value2 + "\t" + Singleton2.singleton2);
        }

        public static int value1 = 5;
        public static int value2 = 3;
        private static Singleton2 singleton2 = new Singleton2();
        private String sign;

        int count = 20;
        {
            System.out.println("count = " + count);
        }

        private Singleton2() {
            value1++;
            value2++;
        }

        public static Singleton2 getInstance2() {
            return singleton2;
        }
    }
```

结果输出:

```
    Singleton value1:5
    Singleton value2:3

    Singleton2 value1:6
    Singleton2 value2:4
```