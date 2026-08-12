# Android八股文


为什么要转客户端

​	主要是因为 安卓客户端 是写 app应用，每次修改代码  编译打包后，可以 把app 安装到 安卓手机 上，可以 实时看到 界面的变化。

​	而且 可以结合 我之前学过的 Java服务端技术，开发 个人app，向 服务端 请求数据。空闲时候，一直 维护 和 更新它。我对这个 还是 比较感兴趣的。

## Activity活动

1. 讲一下安卓的四大组件

​	Activity 活动：主要是 展示 应用程序的界面。可以进行 交互操作，比如 点击按钮，滑动屏幕等。

​	Service 服务：主要用于 在后台执行 长时间操作，比如 音乐后台播放服务，当应用 切换到 后台，音乐 也能在后台 继续播放。

​	BroadCast 广播：主要是在 不同应用 或者 同一应用下的 不同组件（如 Activity、Service） 传递 广播数据。比如 在activity里面 调用 sendBroadCast（）方法，传入 intent数据，发送  广播，多个 广播接收器 都可以接收到。

​	Content Provider 内容提供器：可以在 不同应用进程之间  共享 数据。它其实 是个 数据源，存储   文件，网络数据，SQLite数据库 等等，主要是 数据库 的形式  。使用 Content Resolver对象，调用一些 CRUD方法（insert，update，query，delete方法），传入一个 URI 内容参数，可以 访问  不同应用进程 的数据。



2. 不同组件之间的通信方式

​	activity 之间 通信：

- 通过 BroadCast广播 传递数据
  	发送端 activity 调用 sendBroadCast 方法,传入intent数据,发送广播. 接收端activity 注册广播接收器 BroadCastReceiver接收指定的广播IntentFilter.

```kotlin
override fun onStart() {
    super.onStart()

    val intentFilter = IntentFilter(
        BroadcastConstants.ACTION_SEND_MESSAGE
    )

    ContextCompat.registerReceiver(
        this,
        messageReceiver,
        intentFilter,
        ContextCompat.RECEIVER_NOT_EXPORTED
    )
}
```

- 通过Intent传递数据

​	发送端activity调用startActivity()方法,传入intent跳转到另一个activity。接收端activity 通过GET()方法,接收到传来的intent。

​	activity和service通信：

- **startService**: activity 可以通过INTENT 启动 服务service, service在 onStartCommond()方法里面接收
- **bindService**:Activity 和 Service 绑定，Activity 通过 Service 返回的 IBinder 对象获取 Service 的代理，从而调用 Service 中的方法，实现 Activity 与 Service 的实时交互。

3. activity 活动 的生命周期

生命周期 主要有 6 个 回调函数 ：

(1) onCreate() ：当 新的Activity 创建时 调用。此时，可以进行一些初始化操作，比如加载xml布局，初始化视图。

(2) onStart() ：当 Activity 变得可见，但还未获取焦点时 调用。此时 Activity 不可操作。（可见，不可交互）

(3) onResume() ：当 Activity 获得焦点时 调用 。此时 Activity 可操作 ， 处于  活动状态。（可见，可交互）

(4) onPause() ：当 Activity 失去焦点时 调用。此时 Activity 不可 操作，处于 暂停状态。（可见，不可交互）

(5) onStop() ：当 Activity 完全不可见时 调用。处于 停止状态。（不可见）

(6) onDestory() ：当 Activity 要被销毁时 调用。此时 可以  释放资源。

然后是 单独的一个 回调函数，onReStart() ：当  Activity 从 不可见 变成 可见时 ， 就会调用。主要就是从 onStop（）到 onStart（），开始 重启。比如 切换到桌面  或者 跳转 到 另一个activity 之后，又返回到 原来的activity。



4. 有两个activity，a先加载，a 跳转到 b，a 和 b 分别经历了 哪些 生命周期 。交织的 生命周期 是怎么样的。同时 b 又返回 a，它们又经历怎么样的生命周期

**a 跳转到 b ：**

​	a 的生命周期，会经历 onPause()  、onStop() 。因为 a 跳转到 b 的时候，a会先 失去焦点，所以会调用  onPause() ，进入暂停状态。然后  b 显示在屏幕前台了， a 就不可见了，就会调用 onStop() ，进入停止状态。

b的生命周期，会经历 onCreate() 、onStart() 、onResume() 。因为 b是 新创建的activity，所以会调用 onCreate() 。然后 b 变得可见但还未获得焦点时，就会调用 onStart()  。当 b 获得焦点时，就会调用 onResume() ，此时可操作，进入 活动状态。

**a 跳转到 b：交织的生命周期：**

​	先执行 a 的 onPause() 方法，然后执行 b的  onCreate() 、onStart() 、onResume() ，最后 才是 a的 onStop() 方法。

**b 又跳转到 a ：**

​	a 的生命周期，会经历 onResart() 、onStart() 、onResume() 。因为 a 从不可见到 可见，就会调用 onResart() ，开始重启。然后就是 a可见但未获得焦点，调用 onStart() 。当 a 获得焦点时 调用 onResume() ，此时可操作，进入 活动状态

​	b 的生命周期，会经历 onPause() 、onStop() 、onDestory() 。b 会先 失去 焦点，所以 调用 onPause()，进入暂停状态。然后  a 会显示在屏幕前台了， b 就不可见了，就会调用 onStop() ，进入停止状态。 **又因为是在 b 的界面，按下  back 返回键，b 会 弹出 返回栈，所以就会被销毁，就会调用 onDestory()。(栈顶back会Destory)**



5. 如果 activity 直接按back键 回到桌面 ，然后再回到 activity，经历的生命周期。那如果直接home键呢

按下 **back键** ：

​	activity ，生命周期  会经历 onPause() 、onStop() 、onDestory() 。因为 会先 失去 焦点，所以 调用 onPause()，进入暂停状态。然后 会显示在 手机桌面， activity 就不可见了，就会调用 onStop() ，进入停止状态。又因为 按下  back 返回键，activity  会弹出  返回栈，所以 就会被销毁，就会调用 onDestory() 。

​	当 再回到 activity ，生命周期  会经历 onCreate() 、onStart() 、onResume() 。因为 activity 之前已经被销毁了，所以现在需要 重新创建 ，所以会调用 onCreate()  。然后 activity 变得可见但还未获得焦点时，就会调用 onStart() 。当  获得焦点时，就会调用 onResume() ，此时可操作，进入 活动状态。

按 **home 键**：

​	activity ，生命周期 会先经历 onPause() 、onStop()。因为 会先 失去焦点，所以会调用 onPause() ，进入暂停状态。然后 会显示在 手机桌面，activity 就不可见了，就会调用 onStop()，进入 停止状态。因为按 Home键 ，并不会 把activity 弹出 返回栈，所以不会 被销毁，不会 调用 onDestory()。**（栈顶home不会Destory）**

​	当再回到 activity，生命周期 会经历 onResart()、onStart()、onResume() 。因为 activity 并没有被销毁，所以不需要 重新创建，不会 调用 onCreate()。又因为 从不可见到 可见，就会调用 onResart() ，开始重启。然后 activity 变得可见但还未获得焦点时，调用 onStart() 。当  获得焦点时 调用 onResume() ，此时可操作，进入 活动状态。



6. 横竖屏切换的 Activity 生命周期变化

​	当屏幕从竖屏 切换到 横屏时，系统会销毁 当前 activity 然后 重新创建一个 activity。activity 生命周期会先经历  onPause() 、onStop() 、onDestory() 。然后再经历 onCreate() 、onStart() 、onResume() 。

 横屏 切换到 竖屏，也是类似的。

​	在 AndroidManifest.xml 清单文件中，设置属性 android:configChanges = "orientation"，这样在屏幕旋转时 就不会 重新创建 activity。



7. activity的 返回栈

​	返回栈  就是一种 栈结构，它遵循 先进后出 原则，用于管理 activity。当 启动一个 新activity 时，它就会被 压入栈顶。当  按下 back 返回键，栈顶 activity 就会被弹出，然后  销毁 。



8. activity的**启动模式**有哪些，对应的使用场景

​	主要有 4 个，标准模式、栈顶复用 、栈内复用、单实例模式。

​	标准模式：每次启动  Activity 的时候，不论 它  是否已经存在，都会创建一个 新的activity实例。 适用于  大部分 场景。

​	栈顶复用：启动 activity 时候，如果 返回栈的栈顶  已经存在 该 activity 的实例，就直接 复用，调用 onNewIntent( ) 方法。适用于 频繁要启动  的界面。（当目标 Activity **不在栈顶** 时，**不会复用旧实例，而是创建一个新实例**。）

​	栈内复用：启动 acitivity 的时候，如果 返回栈中 已经存在 该activity 的实例，而是把 该activity 上面的    所有 其他activity 都弹出栈，然后将 该 activity 置于栈顶，进行 复用。常用于 app 首页 界面，无论从app 的   哪个位置  启动首页，都会访问  同一个 activity 实例。

​	单实例模式：只有 该activity 的实例  单独存在 这一个返回栈 。常用于 系统电话界面、 短信界面等，多个应用app 来访问，都会访问 同一个 activity实例。（一个 Activity 独占一个任务栈。）



9. activity之间 如何通信（activity 之间 如何 传递数据）（**TODO：ViewModel**）

- 通过 BroadCast广播 传递数据
  	发送端 activity 调用 sendBroadCast 方法,传入intent数据,发送广播. 接收端activity 注册广播接收器 BroadCastReceiver接收指定的广播IntentFilter.

```kotlin
override fun onStart() {
    super.onStart()

    val intentFilter = IntentFilter(
        BroadcastConstants.ACTION_SEND_MESSAGE
    )

    ContextCompat.registerReceiver(
        this,
        messageReceiver,
        intentFilter,
        ContextCompat.RECEIVER_NOT_EXPORTED
    )
}
```

- 通过Intent传递数据

​	发送端activity调用startActivity()方法,传入intent跳转到另一个activity。接收端activity 通过GET()方法,接收到传来的intent。



10. activity 和fragment 的通信方式（activity 和fragment 之间 传递数据）

**activity -->fragment：**（TODO：Bundle的传递方式）

- 在activity中，创建fragment实例，通过构造函数传递数据；
- findFragmentById()获得fragment实例，调用它的方法传递数据。

**fragment --> activity：**主要通过 接口回调的方式，向 activity 传递数据。

- 首先在 Fragment 中定义回调接口，Activity 实现该接口；Fragment 在 `onAttach()` 中将宿主 Activity 转换为接口类型并保存引用。Fragment通过该引用调用相应的方法就可以向Activity传递数据。

  

11. fragment之间 的通信方式（fragment 之间 如何 传递数据）

通过共享的Activity作为中间人，例如Fragment A向Fragment B传递数据，在Fragment A中先将数据传递给Activity（接口回调的方式），然后Activity再将数据传递给Fragment B（findFragmentById，然后调用Fragment B的方法）；



12. activity 和 fragment 的区别

**生命周期方面**

> **Activity 有自己完整、独立的生命周期；Fragment 也有自己的生命周期，但它必须依附于宿主 Activity，其生命周期上限受 Activity 状态限制，并会受到 Activity 生命周期变化的影响。**

例如：

- Activity 执行 `onPause()`，其中处于前台的 Fragment 通常也会进入暂停状态。
- Activity 执行 `onStop()`，Fragment 也不会继续保持 `RESUMED` 状态。
- Activity 被销毁时，依附于它的 Fragment 最终也会被销毁。

**独立性方面**

Activity可以独立存在，进行交互和展示。Fragment不能独立存在，必须嵌入到activity中，1个activity可以包含多个fragment。



13. fragment 是什么，怎么用，fragment 怎么替换

fragment 碎片，其实 和activity 很像，都是展示 app应用界面，可以从 它们的**区别** 来讲一下 fragment：

**生命周期方面**

> **Activity 有自己完整、独立的生命周期；Fragment 也有自己的生命周期，但它必须依附于宿主 Activity，其生命周期上限受 Activity 状态限制，并会受到 Activity 生命周期变化的影响。**

例如：

- Activity 执行 `onPause()`，其中处于前台的 Fragment 通常也会进入暂停状态。
- Activity 执行 `onStop()`，Fragment 也不会继续保持 `RESUMED` 状态。
- Activity 被销毁时，依附于它的 Fragment 最终也会被销毁。

**独立性方面**

Activity可以独立存在，进行交互和展示。Fragment不能独立存在，必须嵌入到activity中，1个activity可以包含多个fragment。

**使用：**

首先要在 activity 的 xml布局文件，添加一个 <fragment>标签，作为容器 盛放 fragment 。

然后要 自定义一个类，继承 Fragment 父类。重写 onCreateView( )方法，来加载 自己的 xml布局，会返回一个 view视图。然后要重写 onViewCreated（）方法，通过 findViewById（）方法，设置 布局里面的 子视图。

在代码里，通过 FragmentManager 调用 add（）方法，fragment 就添加到了对应的 activity里面 了。

替换 ：要替换成另外一个 fragment的话，就是调用  FragmentManager的 replace（）方法。



14. fragment的生命周期![1740024499792-9fd373c9-fbc8-443b-9fee-d3b5174b24a8](https://cdn.nlark.com/yuque/0/2025/jpeg/40623470/1740024499792-9fd373c9-fbc8-443b-9fee-d3b5174b24a8.jpeg)

15. fragment的返回栈

Fragment 返回栈  是一个栈结构，遵循 先进后出 原则 。用于 管理 Fragment。可以调用 addToBackStack( )方法 ，把 fragment 添加到 返回栈。当按下 back键  ，就会把  当前fragment  从返回栈  弹出。

## Service服务

16. 服务 是什么，服务 service 的启动方式有哪些（startService与bindService 的区别）

Service 服务，主要用于 在后台执行 长时间操作，比如 音乐后台播放服务，当应用 切换到 后台，音乐 也能在后台 继续播放。

服务的启动方式，主要是 startService（）和 bindService（）。它们主要在 **通信方式**、生命周期 有区别：

**通信方式**

- startService(): activity可以启动Service，通过Intent传递数据；Service在onStartCommand()里接收；
- bindService(): activity与Service绑定得到IBinder对象，通过IBinder调用Service的方法来操作service的状态。

**生命周期**

- startService：Service独立运行，一旦启动，不受activity的生命周期的影响。当自己任务处理完成后调用stopSelf方法或者activity调用stopService方法才会销毁。

  - 生命周期是onCreate() -> onStartCommand() -> onDestory(), 在某个activity里，不论startService调用多少次，Service的onCreate()只调用一次，但是onStartCommand()可以调用多次。

- bindService：Service依赖于activity的生命周期，并且所有activity解绑后，Service才会销毁。

  - 生命周期是onCreate() -> onBind() -> onUnbind() -> onDestory()。在某个activity里，无论bindService调用多少次，onCreate()和onBind()只会调用一次，而且不会执行onStartCommand()方法。

  

17. activity 和 service 的通信方式（见16）



18. 服务 service的 生命周期

​	onCreate( )：当 Service服务 被创建时，会调用。可以 进行 初始化 操作。

​	onStartCommand( )：当 activity 通过 startService（）启动Service 时，才会调用。可以接收 传来的 Intent参数。

​	onBind( )：当 activity 通过 bindService（）绑定Service 时，才会调用。会 返回  一个 IBinder 对象，可以调用 Service的方法，来操作 Service的状态，用于 activity 和 Service 交互。

​	onUnBind( )：当 activity 通过 **unBindService（）**解绑 Service 时，才会调用。

​	onDestory( )：当 Service 销毁时，会调用。可以 释放 系统资源。



19. 如何保证 Service 不被杀死

- 使用前台服务（Foreground Service，进程优先级 > 普通Service），通过通知让 Service 运行在前台，提高进程优先级；

- 对于需要长期任务，可以结合 WorkManager、JobScheduler 等系统调度机制，在合适时机重新执行任务；

- 厂商系统应用可以使用系统权限、白名单等机制提高存活能力，但普通应用无法使用。



20. IntentService 了解吗，和 service 的区别

​	IntentService 是 Service服务 的子类。会在 后台 自动开启一个 工作线程 来处理 耗时操作，每次处理完 会自动停止 线程，更加 方便。

​	Service服务 的话，要 手动开启 一个线程，去执行 耗时操作，然后手动关闭，比较 麻烦。



20.1WorkManager

> **WorkManager 是 Android Jetpack 提供的后台任务调度框架，用于执行需要可靠完成的后台任务。它会将任务持久化保存，即使应用进程被杀死，系统也会在合适条件满足时重新调度任务执行。适合执行延迟任务、周期任务和可恢复任务，例如数据同步、文件上传、数据库备份等。**

20.2 Foreground Service

> **Foreground Service 是一种运行在前台的 Service，必须显示一个常驻通知，用于执行用户可感知的长期任务。相比普通 Service，它拥有更高的进程优先级，系统更不容易杀死，但不能保证永远存活。适用于音乐播放、导航、定位、录音等需要持续运行的任务。**





## **BroadCast广播**

21. 广播  是什么，广播分为 哪些种类

​	BroadCast广播，主要是在 不同应用进程 或者 同一应用下的 不同组件（如 Activity、Service） 传递 广播数据。比如 在activity里面 调用 sendBroadCast（）方法，传入 intent数据，发送  广播，多个 广播接收器 都可以接收到。

主要分为，系统广播 和 本地广播。

​	**系统**广播：不同的应用 都可以接收到 广播。比如 手机电池电量 发生变化的 系统广播。

​	**本地**广播：只能在  同一应用下的 不同组件（如 Activity、Service）发送和接收  广播 。不会被 其他应用 获取，所以  更加 高效 和 安全。



还可以分为 标准广播 和 有序广播。

​	**标准**广播：发送广播后，所有的 广播接收器  都会 马上接收到。

​	**有序**广播：发送广播后，会按照 广播接收器 的优先级，从高 到 低 顺序传递。



22. 广播接收器 的 注册方式

​	主要分为 静态注册 和 动态注册。

​	静态注册：静态广播是在 AndroidManifest.xml 中通过 `<receiver>` 声明 BroadcastReceiver，并配置 IntentFilter，我们需要自己创建 Receiver 类并实现 onReceive() 方法，发送广播时，系统会自动匹配并调用 `onReceive()`。

​	动态注册：在代码中创建 Receiver（重写onReceive方法） 和 IntentFilter，（通过 `registerReceiver()` 注册）通常绑定 Activity、Service 等组件的生命周期，只在注册生命周期内有效，（需要手动 `unregisterReceiver()`）。

​	Android 8.0 后对静态注册隐式广播进行了限制，因此实际开发中动态注册和其他组件通信方案使用更多。



23. 怎么发送 广播，怎么接收 广播

​	首先用 动态注册 的方式。直接在 代码里， 定义 一个 广播接收器，重写 onReceive( )  方法，接收 和处理 广播。然后 在activity 里面， 声明 广播接收器 和  IntentFilter对象，添加  categery类别和 action动作，指定 接收 某个广播。然后再调用 registerReceiver（）方法，把它们  传进去，注册 广播接收器。

​	然后在 发送端activity 里面，调用 sendBroadCast（）方法，传入 intent数据，发送 广播消息。在接收端activity，注册的 广播接收器，就能接收到 广播消息。



## Content Provider内容提供器

24. Content Provider 内容提供器 是什么，里面的URI，怎么使用

​	Content Provider 内容提供器，可以在 不同应用进程之间  共享 数据。它其实 是个 数据源，存储 文件 ， 网络数据，SQLite数据库 等等，主要是 数据库 的形式。使用 Content Resolver对象，调用一些 CRUD方法（insert，update，query，delete方法），传入一个 URI 内容参数，可以 访问  不同应用进程 的数据。

​	URI 组成部分，最前面是 固定的 content：// ，中间是 应用程序的包名 比如 com.example.app。最后是  访问哪张表，比如 /user。

​	主要是 在某个应用 里面，定义一个 类，继承 Content Provider内容提供器，里面 重写 一些方法，比如 CRUD方法，query()、insert()、delete()等等。然后在 AndroidManifest.xml 清单文件中， **注册 内容提供器，设置  URI**和 访问权限。

​	然后在 其他应用 里面 ，使用 Content Resolver 对象，比如 调用 CRUD方法，传入 对应 URI参数，就可以 查询 其他应用进程的数据 了。

![1734578952389-c3db3b54-aa89-46f3-a35a-9285832f83fc](https://cdn.nlark.com/yuque/0/2024/png/40623470/1734578952389-c3db3b54-aa89-46f3-a35a-9285832f83fc.png)

25. Content Provider 的原理

​	Content Provider 的底层 其实是 Binder 机制。

​	Binder 是一种 跨进程通信 机制，采用  C/S  架构，包含 4大部分。服务端、客户端、ServiceManager、Binder驱动 。ServiceManager 管理 服务端和 客户端，其中 Binder驱动 就是一个 通信桥梁。

​	服务端 进程，**创建 自己的 Binder实体，起一个名字 ，然后  向 ServiceManager  注册** 。然后 ServiceManager 就会拥有 该Binder实体的 引用。

​	客户端 进程，通过 **Binder驱动**以及服务端Binder端**名字** ， 从 ServiceManager 获取 服务端 的Binder实体 的引用，也就是 Binder代理对象，调用 服务端的方法，进行 通信。

26. Content Provider的底层是 Binder 机制，既然已经有了 Binder 实现 进程之间通信，为什么 还需要 Content Provider

主要从 架构、传输大小 来分析。

架构：一个app 由 业务层、数据访问层、数据层构成。其中业务层，展示 页面数据。数据层， 存储  文件、网络数据、数据库。那么 中间的 数据访问层，可以使用 Content Provider 内容提供器，对业务层 提供统一接口（CRUD操作），访问 数据层。

传输大小：使用 Binder 进行跨进程通信，传输的 数据量 较小。使用 Content Provider 传输数据，是在 Binder机制 的基础上，结合了 **共享内存** 机制，所以可以  传输大量数据。

27. ContentProvider 内容提供器 的权限管理

主要是防止 未授权 的应用程序，访问和修改  Content Provider内容提供器的 敏感数据，提高 数据安全性。

具体在 AndroidManifest.xml 清单文件，在注册 内容提供器 时，可以设置 一些读写权限，比如 android : readPermission 和 android : writePermission。

然后在 其他应用程序的 在AndroidManifest.xml 清单文件，通过<use-permission>标签，申请 权限。

## Handler消息机制

28. 安卓的异步任务的组件有哪些

Thread：主要是通过 继承 Thread类，实现 Runable 接口（重写里面的run()方法），Callable接口（或者 call( )方法）。

线程池：Java的 内置线程池 Excutors，比如 newFixedThreadPool，但是它采用 无界工作队列，可能 导致OOM。所以都是推荐用 自定义线程池，ThreadPoolExcutor()，传入 参数，比如 核心线程数、最大线程数。

HandlerThread：是一个 自带 Handler机制 和 Looper 消息循环的 子线程。

AsyncTask：内部使用 Handler机制 和 线程池。它会把 UI更新 放在 主线程 执行，耗时操作 放在 子线程 执行。

IntentService：是 Service服务 的子类。会在 后台 自动开启一个 工作线程 来处理 耗时操作，每次处理完 会自动停止 线程，更加 方便。

29. 安卓的 线程之间通信方式

​	同步锁：使用  synchronized关键字 或者 ReentrantLock类 等等。只有一个 线程可以 拿到锁 ， 其他线程  只能 阻塞等待，当 锁 释放之后，其他线程 才能获取。

​	wait( ) 和 notify( )方法：可以 阻塞和唤醒 线程，进行 通信。

​	使用 Handler：Handler 创建时会绑定一个 Looper 和对应的 MessageQueue。子线程通过调用主线程 （绑定了主线程 Looper的）Handler 的 sendMessage() 将消息加入主线程 MessageQueue，主线程 Looper.loop() 不断从队列取消息，并分发给对应 Handler 的 handleMessage() 方法执行。因此本质是子线程发送消息，绑定主线程 Looper 的 Handler 在主线程处理消息。

- 一个线程可以拥有多个 Handler，但一个 Handler 只能绑定一个 Looper。
- 一个线程可以绑定 0 个或 1 个 Looper，一个 Looper 对应一个 MessageQueue。
- 子线程：需要自己调用 `Looper.loop()`； 主线程：不需要我们显式调用 `Looper.loop()`



30. 安卓的 Handler 消息机制是什么，原理

Handler 主要用于 线程之间通信。由3个模块组成：Handler、Looper、MessageQueue。

Handler：每个线程可以有 多个 Handler。Handler 内部有 Looper 和 MessageQueue 这两个变量，分别 只有一个，在 构造函数 里面进行关联。调用 sendMessage（）方法 发送消息到  消息队列 MessageQueue   里面。调用 handleMessage（）处理消息。

Looper：每个线程 只能有 一个 Looper。Looper 内部 只有一个  MessageQueue 变量，在构造函数 里面 进行关联。调用 loop（）方法，进行 消息循环，不断去 消息队列 MessageQueue 获取消息，然后再让 Handler 去处理消息。主线程 默认 有一个 Looper ， 所以可以直接 创建 Handler。子线程 必须先要调用 Looper.prepare( ) 方法 创建自己 唯一的Looper ，其实是 存入了 ThreadLocal 里面，然后才能创建 Handler。

MessageQueue：内部有一个 Messages 变量，是一个消息链表，把消息 按照 执行时间的 先后顺序  进行 插入。



31. 一个线程 可以 有多个 Handler吗，又有多少个 Looper，有多少个 MessageQueue（线程 Thread、Handler、Looper、MessageQueue 的关系）

- 一个线程 可以 有多个 Handler，但只有一个 Looper 和 MessageQueue。

- Handler 会先调用 sendMessage（）发送 消息到 MessageQueue，然后依赖 当前线程的 Looper，调用 loop（）方法 进行 消息循环，不断去 消息队列里面 取出消息，然后再让 Handler 去处理消息，调用 handlerMessage（）方法 去处理。



32. 怎么确定 消息Message 是哪个 Handler 发来的，然后 要让哪个 Handler去处理

​	Handler 调用 sendMessage( )方法 发送消息的时候，会**把 Message消息 的 target属性 赋值为 this**   ， 消息 就会 自动关联  当前Handler。

​	Looper 的 loop( )方法，进行 消息循环，会不断地从 MessageQueue中 取出消息 ，然后检查 消息的 target属性，调用  msg.target.dispatchMessage()方法，把消息 分发给 目标Handler 进行处理，调用 handlerMessage（）方法 去处理。



33. 为什么能在 主线程 直接使用 Handler，而 不需要 创建 Looper

应用进程
│
├── 主线程（Main Thread）
│       │
│       └── ActivityThread 对象（负责管理当前应用进程中所有 Activity 的生命周期调度）
│               │
│               ├── 管理 Activity 生命周期
│               ├── 管理 Application
│               ├── 与 AMS/系统通信
│               └── 创建主线程 Looper
│
├── 子线程 Thread-1
│
├── 子线程 Thread-2
│
└── ...

​	主线程本质上是由 ActivityThread.main() 启动的。Android 在启动应用进程时，会自动调用 Looper.prepareMainLooper() 创建主线程 Looper，并通过 ThreadLocal 保存。随后调用 Looper.loop() 开启消息循环，不断从 MessageQueue 中取出 Message，并通过 Message.target 找到对应 Handler，最终调用 Handler 的 handleMessage() 处理消息。



34. 子线程 可以创建 Handler 吗，为什么报错，如何在 子线程里面 使用  Handler

​	在子线程中 不可以 直接创建 Handler，会报错。因为在 Handler 的构造函数里面，会调用 Looper.myLooper() 方法，获取 当前线程的 Looper。如果 子线程 没有创建自己的 Looper，那么 就会 抛异常 报错。

​	所以，子线程 首先要 手动调用 Looper.prepare( )方法 创建自己的 Looper ，（隐式）存入 threadLocal  。

​	然后就可以  在子线程中  创建 Handler 了，调用 sendMessage()方法，把消息 发送到  MessageQueue  消息队列。

​	最后记得  **手动调用 Looper.loop( )方**法，进行 消息循环，不断地从 MessageQueue 取出消息，然后 分发给 对应的Handler 进行处理，调用 handlerMessage（）方法 去处理。



35. 为什么不能 在子线程 更新UI，如何在 子线程 更新 UI

​	因为 安卓的UI组件是 线程不安全的。比如  线程A 正在读取 UI组件 的信息，线程B 同时 在修改它，这可能会导致 数据不一致 问题，有时候也会导致 界面混乱 和 程序崩溃 等问题。所以不能在 子线程  更新UI。

​	如果 要在子线程 中 更新UI：

​	使用 Handler：主要用于 线程之间通信 ，由3个模块组成，Handler、Looper、MessageQueue。 子线程  调用 (绑定了主线程looper的)Handler 的 sendMessage( )方法 ，把 更新UI的消息 发给 主线程的 MessageQueue 。然后主线程的 Looper 会调用 loop（）方法 ，进行消息循环 ，从 消息队列 MessageQueue 取出 消息，然后 让 Handler 去 处理消息，调用 handlerMessage( )方法 去处理，在 主线程 更新UI。（由绑定了主线程 Looper 的 Handler，在主线程中处理消息，然后执行 UI 更新。）

​	使用 runOnUiThread( )方法：`runOnUiThread()` 本质是 **Handler 的 post 机制**，通过向主线程 **MessageQueue 投递 Runnable，让主线程 Looper 调度执行 Runnable.run()**，从而实现子线程切换到主线程更新 UI。它不会调用 handleMessage()，因为 post 处理的是 Runnable 回调。



36. Handler 的 send( ) 和 post( ) 的区别

​	send( )方法：发送 Message 消息，可以指定 what的属性值，还可以 携带数据，发给 MessageQueue 消息队列 。然后 Looper 会调用 loop（）方法 ，进行消息循环 ，从 消息队列 MessageQueue 取出 消息，再让 Handler  去处理消息，会在 handleMessage（）方法里面 处理。

​	post( )方法：发送 Runnable 任务，它是一个接口，里面有一个 run()方法 ，发给 MessageQueue 消息队列 。它不会在 Handler的 handleMessage()方法 里面处理，而是 直接在 run（）方法处理。



37. View.post( ) 和 Handler.post( )的区别

​	`View.post()` 通常是将 Runnable 任务发送到 View 所在线程执行，而 Android 中 View 默认都在主线程创建和使用，因此通常表现为发送到主线程执行。

​	`Handler.post()` 则更加通用，它依赖 Handler 绑定的 Looper。创建 Handler 时可以指定不同线程的 Looper，因此 Runnable 可以在对应线程执行。



38. Handler的延时消息怎么实现的，原理

​	当调用 Handler 的 sendMessageDelayed( )方法，就会发送 延时消息。每个消息 都有一个 when 属性 ，表示 该消息 什么时候  被处理。会把消息  按照 执行时间的先后顺序 插入到 MessageQueue消息队列。

​	然后 Looper 会通过 loop（）方法，进行 消息循环，不断从 消息队列 MessageQueue 获取消息，检查链表头节点 的消息。这里有 2 种情况：

​	如果 when属性 小于等于 now当前时间，就说明 消息的 处理时间到了，然后 分发给 对应的Handler 进行处理，调用 handlerMessage（）方法 去处理。

​	大于的话，就说明 消息的 处理时间 还没到，Looper 会计算需要 等待的时间 也就是 when - now ，调用next( )方法，进入 休眠状态。时间到了，会 自动唤醒。本质上是 操作系统底层的 epoll机制，通过 epoll_wait( )函数，来实现的。

​	如果此时，发来一个 消息，when 执行时间早于当前等待时间时，就会 调用 nativeWake( )方法，去唤醒 Looper，处理 消息。本质上是 操作系统底层的 epoll机制，监听 fd文件描述符，来实现的。



39. Handler里面的同步屏障了解吗，怎么使用（设置消息的优先级）

​	默认发给 MessageQueue 的消息都是 同步消息，会按照 执行时间 进行 顺序处理。

​	同步屏障会阻塞所有同步消息的处理，只允许标记为异步的 Message 越过屏障执行。它的目的不是优先处理新消息，而是提高某些高优先级任务（如 UI 绘制）的响应

​	在插入消息的 时候，要调用 Message的 msg.setAsynchronous(true)方法，插入 异步消息。（从代码角度，本质上是调用 postSyncBarrier( )方法，插入同步屏障。）



40. Handler里面的空闲消息，IdleHandler了解吗

IdleHandler 其实是 MessageQueue 的内部接口，里面有 queueIdle( )方法，其实就是 空闲消息。

当  MessageQueue消息队列中的 消息  都被处理完毕，此时就处于 空闲状态。这时候 就会 调用 IdleHandler 接口的 queueIdle( )方法，执行 空闲消息。



41. Handler 使用不当 造成的内存泄露，以及 如何解决

在 Activity 中使用 **匿名内部类**或 **非静态内部类** 创建 Handler，会默认持有 外部类activity 的引用。当 activity 要销毁的时候，但是 Handler 还在处理消息，仍然持有 对activity的引用，activity 就不能被  GC垃圾回收，会造成 内存泄漏。

解决方法，使用 **静态内部类** ，这样默认就不持有 activity的引用了 ， **同时**使用  弱引用 。 或者在 activity 要销毁的时候，执行一下 removeCallbacksAndMessages（）方法，移除当前 所有消息。



42. Looper 死循环，为什么 应用不卡死

​	主要是 Looper 调用 loop( )方法，会进行 消息循环，不断去 消息队列 MessageQueue 获取  消息。当 没有消息 或者 消息还没到执行时间 when，会调用 next( )方法，Looper 就会 休眠，此时 不会占用 CPU 资源，不会造成 应用卡死。时间到了，会 自动唤醒。本质上是 操作系统底层的 epoll机制，通过 epoll_wait( )函数，来实现的。

​	如果此时（当 Looper 正处于休眠等待状态时），发来一个 消息，when 执行时间 更早，就会 调用 nativeWake( )方法，去唤醒 Looper，处理 消息。本质上是 操作系统底层的 epoll机制，监听 fd文件描述符，来实现的。



43. 你对 HandlerThread的了解，原理，怎么使用

​	HandlerThread：是一个 自带 Handler机制 和 Looper 消息循环的 子线程。

​	从代码使用：首先创建 HandlerThread，然后调用 start( )方法，启动 子线程。其实会调用 内部写死的一个 run( )方法，里面创建 当前 子线程的 Looper，自动调用  loop（）方法，开启 消息循环。

​	然后要创建 一个Handler，传入 HandlerThread 的Looper，以后发过来的消息 都在 HandlerThread子线程中 处理。



44. Handler 和 HandlerThread 的区别

​	使用 Handler：主要用于 线程之间通信 ，由3个模块组成，Handler、Looper、MessageQueue。

​	HandlerThread：是一个 自带 Handler机制 和 Looper 消息循环的 子线程。



45. 你对 AsyncTask 的了解，原理，怎么使用

​	AsyncTask：内部使用了 Handler机制和 线程池。它会把 UI更新 放在 主线程 执行，耗时操作 放在 子线程 执行。

​	从代码使用上的话：主要是定义一个实现类，继承 AsyncTask ，要传入三个 泛型参数<>。第一个是指 异步任务的参数类型，第二个是指 异步任务的 执行进度类型 比如 Integer整型，第三个是指 异步任务执行完 的返回类型。

​	以及 要重写 一些方法，比如 onPostExecute( )  ，用于 主线程的 UI  更新。

​	最后调用  execute( )方法，启动 执行。



46. AsyncTask 使用不当 会带来哪些 问题，如何解决

​	AsyncTask  必须在 主线程中 创建，然后调用 execute( )方法 启动。

​	AsyncTask的 execute( )方法 只能执行一次，如果 多次执行 就会报错。

​	内存泄漏：在activity 使用  匿名内部类 或  非静态内部类 创建 AsyncTask ，它默认持有 对activity 的引用。当 acitivity 准备销毁的时候，但是 AsyncTask  还在处理任务，还持有对 activity 的引用，就会导致 activity 不能被 GC垃圾回收。解决方法，把它声明成 静态内部类，同时使用 弱引用 。或者在 activity 要销毁的时候，手动调用 cancel(）方法，停止 AsyncTask。

## 跨进程通信

47. 安卓的跨进程通信的方式（ IPC 通信方式）

​	因为 安卓是基于 linux系统的，所以 传统的linux 进程通信方式：

![1740128195024-8d99daa0-b6f0-485f-bbe8-bddbf3507e20](https://cdn.nlark.com/yuque/0/2025/jpeg/40623470/1740128195024-8d99daa0-b6f0-485f-bbe8-bddbf3507e20.jpeg)

然后是一些  安卓的  进程通信方式：

​	BroadCast广播：主要是在 不同应用进程 或者 同一应用下的 不同组件（如 Activity、Service） 传递 广播数据。比如 在activity里面 调用 sendBroadCast（）方法，传入 intent数据，发送  广播，多个 广播接收器 都可以接收到。

​	Content Provider 内容提供器：可以在 不同应用进程之间  共享 数据。它其实 是个 数据源，存储的是 文件，网络数据，SQLite数据库 等等，主要是 数据库 的形式  。使用 Content Resolver对象，调用一些 CRUD方法（insert，update，query，delete方法），传入一个 URI 内容参数，可以 访问  不同应用进程 的数据。

​	Binder ：是一种 跨进程通信 机制，采用 C/S 结构，包含 4大部分。服务端、客户端、ServiceManager、Binder驱动 。ServiceManager  用于管理 服务端和 客户端，其中 Binder驱动 就是一个 通信桥梁。

48. Binder 是什么，原理

​	Binder 是一种 跨进程通信 机制，采用 C/S  架构，包含 4大部分。服务端、客户端、ServiceManager、Binder驱动 。ServiceManager  用于管理 服务端和 客户端，其中 Binder驱动 就是一个 通信桥梁。

​	服务端：创建 自己的 Binder实体，起一个名字 ，然后  向 ServiceManager  注册 。然后 ServiceManager 就会拥有 该Binder实体的 引用。

​	客户端：通过 之前起过的名字，以及 Binder驱动， 从ServiceManager 获取服务端的 Binder实体 的引用，也就是 Binder代理对象。比如调用 服务端的方法，进行 通信。

![1734453977340-19e8d9a4-67e7-4176-b520-6e563b3f4cda](https://cdn.nlark.com/yuque/0/2024/png/40623470/1734453977340-19e8d9a4-67e7-4176-b520-6e563b3f4cda.png)



49. Binder 相比于 Linux的传统进程通信方式 有什么优势，安卓 为什么要用 Binder

​	Binder 的优势，主要体现在 传输性能、独立性。

​	传输性能：因为之前的 管道、消息队列 进程通信方式，都需要 2次 数据拷贝，数据 要先从  发送方缓存区 拷贝到 内核缓存区，然后再从 内核缓存区 拷贝到 接收方缓存区。而 Binder 只要 1次 数据拷贝（ 因为使用 mmap内存映射 技术，把 发送方 和 接收方 都映射到 内核缓冲区），速度 更快，性能 更好。

​	独立性：Binder是 客户端，服务端的 架构，更加 独立，稳定性 好。



50. AIDL 是什么，数据流向，怎么使用，原理

​	AIDL 安卓接口定义语言，用于 跨进程 通信。底层其实是 Binder 机制。

​	AIDL 的 数据流向，有 3 种，分别是 in、out、inout 。

​	in：传输的数据 从客户端 流向 服务端。服务端 对数据 的修改 不会同步 给客户端。

​	out：传输的数据 从服务端 传递到 客户端。服务端 对数据 的修改 会同步给 客户端。

​	inout：数据在  客户端和服务端 之间  双向传输。

怎么使用 AIDL：

​	先创建一个 .aidl文件，在里面定义 接口和方法。

​	然后  编译 项目，会生成 对应的 .java类，发现里面 有一个 Stub类。

​	在 服务端，比如在 自定义的 Service服务类 里面，创建一个内部类，去继承 生成的 Stub类，重写 接口方法，然后 通过 onBind（）方法 返回 对应的 Stub实例。

​	在 客户端，比如在 acitivity 里面，调用 bindService( )方法，绑定 Service服务，就能获得 对应的  Stub  实例，然后就可以调用 服务端的 方法和数据了。

## View视图

51. view 的 绘制流程

​	view 的绘制流程，分为 3 个 步骤，测量、布局、绘制。

​	测量：主要是测量 视图的大小。每个视图 都会 被调用 measure（）方法，会接收 2 个参数，分别是 宽高的测量规范MeasureSpec，它们(宽高的MeasureSpec)是 父视图 计算好的，传过来 的 。然后就会调用 重写过的 onMeasure（）方法，测量出 视图的 最终大小。如果 当前视图 是 ViewGroup，会一直 循环遍历 子视图 ， 先测量 出 所有 子视图 的大小，累加 或者 比较 之后，才知道 自己的大小。

​	布局：主要是确定 子视图在 父视图中的 位置。每个视图都会被调用 layout（）方法，会接收 4 个 参数，left、top、right、bottom，相对于 父视图的 各个边界距离。 然后就会调用 重写过的 onLayout（）方法，确定 视图的 最终位置。如果  当前视图  是 ViewGroup，先确定 自己的位置，然后再 一直 循环遍历 子视图 ，才确定 子视图的 位置。

​	绘制：主要是把 视图 绘制到 屏幕上。每个视图都会被调用 draw( )方法， 接收一个 Canvas 参数，方法内部 也可以使用 Paint画笔，用于 绘制形状、颜色、背景。然后会调用 重写过的 onDraw( ) 方法，进行 绘制。如果 当前视图 是 ViewGroup，先绘制 自己，再一直 循环遍历 子视图，才绘制 子视图。



52. MeasureSpec 是什么，三种 测量模式的区别 和应用

​	MeasureSpec 测量规范，32位 二进制数。它包含 2 个 部分，测量模式（高2位）和 视图大小（低30位）。它会在 父视图里面 先计算出来，传给 子视图，主要根据 测量模式，来确定子视图的 大小。

​	主要有 3种 测量模式：

​	UnSpecified（不指定）：父视图 不指定 子视图的大小，子视图的大小 可以是 任意的。主要用于 系统内部测量。

​	Exactly（精确）：父视图  指定了 子视图 的大小，子视图的大小 必须是 这个值。主要用于 子视图的 LayoutParams 参数是 match_parent 或 具体大小（比如100dp）。

​	At_Most（最多）：父视图 指定了 子视图  最大大小，子视图的大小 不能超过 这个值。主要用于 子视图的 LayoutParams 参数是 wrap_content 。



53. 怎么测量 view 的宽高

​	MeasureSpec 测量规范，32位 二进制数。它包含 2 个 部分，测量模式（高2位）和 视图大小（低30位）。它会在 父视图里面 先计算出来，传给 子视图，主要根据 测量模式，来确定子视图的 大小。

​	主要有 3种 测量模式：

​	UnSpecified（不指定）：父视图 不指定 子视图的大小，子视图的大小 可以是 任意的。主要用于 系统内部测量。

​	Exactly（精确）：父视图  指定了 子视图 的大小，子视图的大小 必须是 这个值。主要用于 子视图的 LayoutParams 参数是 match_parent 或 具体大小（比如100dp）。

​	At_Most（最多）：父视图 指定了 子视图  最大大小，子视图的大小 不能超过 这个值。主要用于 子视图的 LayoutParams 参数是 wrap_content 。



​	普通 View：因为 不是ViewGroup，所以没有 子视图，不需要 循环遍历 子视图，测量 大小。直接在 重写过的onMeasure（）方法里面，根据传入的 MeasureSpec 测量规范，就能 测量 自己的宽高大小。

​	ViewGroup：因为有 子视图，所以在重写过的 onMeasure（）方法里面，先 循环遍历 子视图，先测量出 所有 子视图的宽高大小，比较 或者 累加之后，最终才 测量出 自己的宽高大小。



54. 怎么 自定义 view

主要分为  自定义 普通View  和   自定义ViewGroup。

​	自定义普通 View：因为不是 ViewGroup，没有 子视图，不需要 确定 子视图的位置。只需要 重写 onMeasure（）测量方法 和onDraw（）绘制 方法。

​	自定义 ViewGroup：包含  子视图，所以需要  确定 子视图的位置。需要 重写 onMeasure（）测量方法 和 onLayout（）布局方法。

​	自定义 普通View：首先要 继承View类，定义 一些构造方法，保证在 代码中 或 xml布局文件 都能 正确 初始化。然后要重写 onMeasure（）测量方法，根据 传入的 MeasureSpec测量规范，测量出 自己的宽高。然后重写 onDraw（）绘制方法，根据传入的 Canvas参数，以及内部使用 Paint 画笔，进行 绘制。

​	自定义 ViewGroup： 首先要继承 ViewGroup类，定义一些构造方法，保证在 代码中 或 xml布局文件 都能 正确 初始化 。然后 要重写onMeasure（）测量方法，会先 一直 循环遍历 子视图 ， 先测量 出 所有 子视图 的大小，累加 或者 比较 之后，才知道 自己的大小。然后要重写  onLayout（）布局方法，先确定 自己的位置，然后再 一直 循环遍历 子视图 ，才确定 子视图的 位置。



55. 自定义view的  自定义属性 知道吗

​	自定义属性   只能用在   自定义View  里面。

​	首先在 res/values目录，创建一个 attrs.xml文件。每一个<>标签，就是一个 自定义属性。设置 name属性名，以及 format 类型，是 颜色 还是 文字类型 啥的。

​	然后 在xml布局文件里面 ，定义 命名空间，就可以 在自定义View 直接用 app：属性名，传入 属性值。



56. view的事件分发机制（触摸事件）的原理，经历 哪些函数

事件分发的流程：

​	当一个 触摸事件 发生 ，先传给 最上层的 ViewGroup 类，会调用 dispatchTouchEvent（）方法，内部会调用 onInterceptTouchEvent（）方法 ，检查 是否要 拦截 事件，如果返回 true，表示拦截， 事件就不会 分发给 子视图，让自己处理事件。

​	如果返回 false，不拦截 事件，判断 子视图 是否为 空null。如果 为空，调用 super.dispatchTouchEvent（）方法，内部会调用 onTouchEvent（）方法 ，其实就是让 ViewGroup 自己去 处理事件 。如果 不为空 ，就会调用 child.dispatchTouchEvent（）方法，内部也会调用 onTouchEvent（）方法，让子视图 去处理事件。如果子视图 又是 ViewGroup，又会 进行 类似判断。所以会 一直向下 传递事件，会传给 最底层的 子视图。

​	如果 子视图 没有处理事件(onTouchEvent())，返回 false，那么事件 就会 向上 分发，让 父视图 继续处理。



57. 事件分发机制，如何拦截 某一个事件

​	直接 重写 dispatchTouchEvent（）方法，在 ViewGroup 事件分发的 入口处拦截 某个事件，返回 true，不分发给 子视图。

​	重写 onInterceptTouchEvent( ) 方法，这个方法 是 ViewGroup类 特有的 ，可以拦截 某个事件，然后 返回 true，不分发给 子视图。

| 方法                      | 调用者      | 作用         | 返回值意义                |
| ------------------------- | ----------- | ------------ | ------------------------- |
| `dispatchTouchEvent()`    | 所有View    | 事件分发入口 | true-已处理，false-未处理 |
| `onInterceptTouchEvent()` | 仅ViewGroup | 是否拦截事件 | true-拦截，false-不拦截   |
| `onTouchEvent()`          | 所有View    | 事件处理     | true-已消费，false-未消费 |



58. 上下滑动 和 左右滑动冲突 是什么，事件冲突 是什么，怎么解决

​	比如 ViewPager 内部嵌套了 ListView，当在这个区域 进行触摸操作，可能本意是在 ViewPager 左右滑动，因为系统  会错误识别为  ListView 的上下滑动，就产生了 滑动冲突。

如何解决：

​	重写  父视图ViewPager 的 onInterceptTouchEvent（）方法  ， 去比较下 触摸点  在水平 和 垂直方向的 移动距离的大小，来决定拦截 哪种 滑动事件。如果 水平方向的移动距离 大于 垂直方向，那么就拦截，也就是让 ViewPager 处理 左右滑动 事件。否则的话，就会让 ListView  处理上下滑动 事件。



59. OnClickListener、OnTouchEvent 、OnTouchListener 三者之间 的区别

​	OnClickListener ：是一个接口，里面有一个 onClick（）方法，需要我们 重写。它接收 View 参数。它只能处理 简单的 点击事件。

​	onTouchEvent：是一个方法，它是 view父类 默认提供的，只能在 自定义View的时候，才可以 重写 这个方法。

​	OnTouchListener：是一个接口，里面有一个 onTouch（）方法，需要我们 重写。它接收 MotionEvent 参数。可以处理 很多复杂的 触摸事件。

它们的 执行优先级 :  OnClickListener< onTouchEvent < OnTouchListener。



60. dpi，dp，sp，px  这几个单位 的区别

​	dpi，不能用于 UI组件的大小。它是 像素密度，表示 每英寸屏幕 有多少个 px像素。dpi 一般是 160，320 等等。dpi 越大，显示 越清晰，px像素点个数 也越多，但是 每个像素点 也越小。

​	dp（用于 UI组件的大小 ）和 sp （用于 文字的大小 ， 还能 根据系统 进行 缩放）。它们都是 密度无关像素。同一个 dp值，在不同 手机屏幕dpi 显示的大小  都是 一致的。因为有公式，px = dp *（dpi/160），dp 会转为 px 像素单位。同一个 dp值，在更大dpi 的屏幕 计算出来的 px像素点个数 更多，但是 每一个 px像素点 就越小，所以 UI组件的大小 是一致的。

​	px 像素，不推荐 使用。因为 同一个 px值  在不同屏幕dpi，显示的大小 是 不一样的。在更大dpi 的屏幕 的每个px像素点 更小。



61. 屏幕适配 怎么实现

​	使用 相对布局 和 约束布局：这两种布局，能够根据 屏幕尺寸， 动态调整 UI组件之间的 相对位置。

​	使用 dp 和 sp 单位：它们都是 密度无关像素。同一个 dp值，在不同 手机屏幕dpi 显示的大小 都是 一致的。因为有公式，px = dp *（dpi/160），dp 会转为 px 像素单位。同一个 dp值，在更大dpi 的屏幕 计算出来的 px像素点个数 更多，但是 每一个 px像素点 就越小，所以 UI组件的大小  是一致的。

​	使用 尺寸 修饰符：在 res 目录 创建 不同的 布局文件夹，比如 layout-sw600dp 文件夹、layout-sw700dp文件夹。在不同的 手机屏幕尺寸，会加载 不同的 xml布局文件。



62. 安卓的 常见UI组件 有哪些

TextView：本文框。

Button：按钮，用于 触发 点击事件。

ImageView：显示 图片

ListView：用于 显示列表。

RecyclerView：也是  显示列表，性能 更好。



63. view 和 viewGroup 的区别

View ：是单一 的UI组件，不能包含  子视图。常见的包括  TextView文本框、Button按钮  等等。

ViewGroup ：是个 容器，可以包含  多个 子视图View 。常见有 ListView。



64. 普通 View 和 SurfaceView 的区别

​	普通 View：基于 CPU，在主线程 进行绘制。当界面上的 View视图 较多 或者 view绘制过程 较为复杂时，可能会阻塞  主线程，导致 界面卡顿。适用于简单的 文本、按钮 等场景。

​	SurfaceView：基于 GPU，可以使用 硬件加速，在一个 独立的线程 进行绘制。不会 阻塞 主线程  ，性能 很高，可以保证  流畅度。适用于 游戏、音视频、性能优化 等场景。



65. webview 了解吗，怎么 和 js通信，怎么优化

​	webView 组件，可以 加载显示 网页内容。不需要  再去打开 外部的 浏览器了。

​	如何使用它：先在 xml布局文件 定义 webView，然后在代码里面 调用 loadUrl（）方法 ，传入 网页url地址，就可以 显示网页了 。

**怎么 和 js通信：**

​	先 定义一个 类，里面定义 一些方法。然后调用 webView的 addJavascriptInterface（）方法，传入 这个 类。然后在 本地的 网页文件 的代码里，通过 window. 调用  安卓 提供的方法。

​	同时，在安卓的代码里面，可以调用  webView.evaluateJavascript( )方法，调用 网页的js。

​	webView怎么优化：设置 硬件加速。设置 缓存策略，优先使用缓存，没有缓存 再从网络加载。



66. 安卓的 动画了解吗，分类

视图动画：又分成 2 类

​	逐帧动画：定义一帧帧的图片 。

​	补间动画：定义 开头帧 和 结束帧，中间过程就是 大小、平移、透明度 等变换。

属性动画：

​	对于 任何属性 都可以 进行变换。比如 颜色 、形状 等等。

过渡动画：

​	比如在 两个 activity切换的时候，加入一些 动画效果，更加 自然。



67. ListView 和 RecycleView 的区别

**性能**：

​	ListView：在上下滑动的时候，会不断地 创建和销毁 视图，性能 开销大，性能 很差。

​	RecyclerView：内置 ViewHolder 模式，复**用 和缓存** 视图，减少 创建和销毁视图 的开销，性能 好。

**滑动方向：**

​	ListView：主要用于垂直列表展示，滑动方向比较固定；

​	RecyclerView ：通过 LayoutManager 可以支持垂直列表、水平列表以及网格布局，扩展性更强

**数据更新**：

​	ListView：增删改 某个item之后，会更新  整个Listview，带来额外的开销，性能较差。

​	RecyclerView：只会更新 某个item，减少开销，性能好。



68. ListView 怎么进行优化

**视图复用和缓存：**

​	因为每次调用 getView( ) 方法的时候，都会 创建一个 新的视图 ， 所以需要使用 convertView 参数，进行视图复用。

### 1. 使用 convertView 复用 Item 布局（视图复用）

- ListView 在滚动时，如果每个 item 都重新创建 View，会产生大量对象创建和销毁，导致：

  - GC频繁

  - 内存占用增加

  - 滑动卡顿

所以利用 `getView()` 方法中的 `convertView`：

```java
@Override
public View getView(int position, View convertView, ViewGroup parent) {

    if(convertView == null){
        convertView = LayoutInflater.from(context)
                .inflate(R.layout.item, parent, false);
    }

    return convertView;
}
```

原理：

- 屏幕外滑出去的 item 不会销毁
- ListView 会把它传给后面的 item 使用
- 避免重复 inflate 布局

​	但是 每次都要 调用  convertView参数的  findViewById( ) 方法 来查找 视图，性能很差，所以还要 使用 ViewHolder模式 ，来缓存 视图。

即使复用了 `convertView`，如果每次 `getView()` 都执行：

```java
TextView title = convertView.findViewById(R.id.title);
ImageView img = convertView.findViewById(R.id.img);
```

仍然会不断遍历 View 树查找控件。

所以使用 ViewHolder：

第一次创建：

```java
class ViewHolder{
    TextView title;
    ImageView img;
}
```

保存控件引用：

```java
if(convertView == null){

    convertView = inflater.inflate(
        R.layout.item,
        parent,
        false
    );

    ViewHolder holder = new ViewHolder();

    holder.title = convertView.findViewById(R.id.title);
    holder.img = convertView.findViewById(R.id.img);

    convertView.setTag(holder);

}
else{

    holder = (ViewHolder) convertView.getTag();

}
```

之后：

```
第一次：
inflate布局（布局->视图）
↓
findViewById查找控件
↓
保存引用


后续：
直接getTag()
↓
拿到控件引用
↓
无需findViewById
```



69. RecyclerView 是怎么进行 视图复用 的

​	它使用了  ViewHolder模式，来进行 视图复用 和缓存，减少 创建和销毁视图 的开销，提高 性能。

**主要流程：**

1. 会先在 RecyclerView的 Adapter适配器 ，**会根据屏幕尺寸和 Item 布局大小，计算出当前屏幕最多能容纳的 Item 数量（例如 10 个），并额外预取 1~2 个。**

2. 在每个ViewHolder 的构造函数中，会调用 findViewById（）方法，缓存 每个Item 的 view视图。

3. 然后 绑定数据。

4. 当一个  Item  滑出屏幕时，会把  对应的ViewHolder  放入缓存池 。

5. 然后 要显示 新的Item时，会去检查  缓存池中，是否有 可用的 ViewHolder。

- 如果 有，就会复用 这个ViewHolder，然后 重新 绑定数据。

- 如果没有，就会创建 新的ViewHolder，重新 绑定数据。

### 完整代码（每一步都标了注释）

```kotlin
/**
 * 假设数据有 1000 条
 */
class MyAdapter(private val dataList: List<String>) : 
    RecyclerView.Adapter<MyAdapter.MyViewHolder>() {

    // ============================================================
    // 步骤 1：创建 ViewHolder 对象
    // 注意：这个方法不会被调用 1000 次！只调用 "屏幕可见数(10) + 预取数(2)" ≈ 12 次
    // 当缓存池中没有可用的 ViewHolder 时，系统才会调用此方法
    // ============================================================
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        // 步骤 1（具体实现）：加载布局文件
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_text, parent, false)
        
        // 步骤 1（结束）：将 View 传给 ViewHolder 的构造函数，创建 ViewHolder 实例
        return MyViewHolder(view)
    }

    // ============================================================
    // 步骤 3：绑定数据（首次显示时调用）
    // 步骤 5a：当从缓存池中拿到可复用的 ViewHolder 时，也会调用此方法重新绑定数据
    // ============================================================
    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        // 步骤 3 & 5a（具体实现）：获取当前位置的数据
        val currentData = dataList[position]
        
        // 直接使用 ViewHolder 中已经缓存好的 View 来设置新数据
        // 注意：这里没有 findViewById，直接用缓存的引用
        holder.textView.text = currentData
        holder.positionText.text = "索引: $position"
    }

    // 返回数据总量（仅用于告诉 RecyclerView 滚动条范围和总个数，不参与创建对象）
    override fun getItemCount(): Int = dataList.size // 返回 1000

    // ============================================================
    // 步骤 2：在每个 ViewHolder 的构造函数中缓存视图
    // ============================================================
    class MyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // 步骤 2（具体实现）：在构造函数执行时，立即调用 findViewById
        // 并将结果存为成员变量（缓存起来），之后永远不再重复查找
        val textView: TextView = itemView.findViewById(R.id.tv_content)
        val positionText: TextView = itemView.findViewById(R.id.tv_position)
        
        // 如果有图片、子布局等，也都在这里一次性缓存
        // val icon: ImageView = itemView.findViewById(R.id.iv_icon)
    }
}
```



70. RecyclerView 缓存的原理，四级缓存

一级缓存 ：缓存 屏幕上可见的，正要 数据更新的  ViewHolder。

二级缓存 ：缓存 快速滑出屏幕的，又要 重新显示的 ViewHolder。容量有限，默认大小是 2 。

三级缓存 ：自定义 缓存扩展接口，实现 复杂的缓存逻辑，更灵活。

四级缓存 ：是一个 共享缓存池，缓存 滑动过程中 移出屏幕、大量、所有类型的 ViewHolder。二级缓存 满的时候，ViewHolder 就会被 放入这里。



## xxxLayout布局

71. 常见的布局 有哪些，性能上的 区别

​	LinearLayout 线性布局：按照 水平 或 垂直方向 排列 子视图

​	FrameLayout 帧布局：所有 子视图 都堆叠在布局的左上角，后面添加的 子视图 会  覆盖 前面的 子视图

​	RelativeLayout 相对布局：子视图的位置是  相对于  其他子视图的。

​	ConstraintLayout 约束布局：可以使用 各种 约束属性，来定义 子视图的 位置。

性能上：

​	LinearLayout线性布局 和 FrameLayout帧布局：因为 子视图的位置计算 比较简单 ，计算 开销小 ， 性能较好。

​	RelativeLayout相对布局：因为 布局中的 子视图 越来越多 时，相对位置  越来越 复杂，计算 开销大， 性能较差。

​	ConstraintLayout 约束布局：在复杂布局，它可以  减少 多层嵌套布局 ，减少 位置计算，来提高 性能。



72. 布局 怎么优化

​	使用 ConstraintLayout约束布局。在复杂布局，它可以减少 多层嵌套布局 ，减少 位置计算，来提高 性能。

​	使用 ViewStub 。它是一个 空 视图，占用 极少的内存。只有 在需要时，才会被替换为 实际的视图。

​	使用 RecyclerView 来代替 ListView。因为它内置 ViewHolder模式，进行 视图复用和缓存，减少 创建和销毁视图 的开销，提高 性能。



## Context

73. 对于 Context 的了解，如何获取 Context

​	Context  是 安卓应用程序的 上下文环境。比如在 一个activity 里面跳转到  另一个activity，或者 启动一个 Service服务 ，都需要 传入 intent，然后 intent 的构造方法，要传入一个 Context 参数。

​	怎么获取 Context：比如在 Acitivity 里面，因为它 继承了 Context类 ，所以直接   this  就能拿到 。 在 fragment 里面，可以通过 `requireActivity()`或者 `requireContext()`方法，也能拿到。



## Intent

74. Intent 是什么

​	Intent 意图，用于 不同应用进程 或 同个应用中 不同组件（比如 activity、service） 传递数据。比如在 activity里面， 调用 startActivity（）方法，传入intent，跳转到 另外一个 activity，传输数据。



75. 显式和隐式 Intent 的区别

​	显式 Intent：比如  调用 startActivity ( )方法，传入 intent，会**指定  目标组件的 类名**，传入 activityB .class，可以直接 跳转到 activityB。适用于  **同一应用**的 不同组件中 使用，因为明确 类名是什么。

​	隐式 Intent：比如 调用 startActivity（）方法，传入intent，不会指定 目标组件的 类名，只是设置  action动作、category类别。系统 会去 **自动匹配** 对应的 activity组件，然后 启动。适用于 **不同应用**之间，比如 文件分享功能，从qq 分享到 微信。



76. 隐式Intent 的匹配规则，可以设置 多个action，多个category 吗

​	在 AndroidManifest.xml清单文件 里面，不同组件（比如 activity、Service）的 <intent-filter>标签，可以设置  一个 或 多个 action动作。

​	当Intent  能匹配 任意一个 action动作 ，就会 匹配成功。比较 宽松。

​	也可以设置 一个 或 多个 category类别  ，只有当Intent  匹配   所有 category类别，才算 匹配成功。比较 严格。

​	如果 有多个组件 都被 匹配成功，就弹出 对话框列表，自行选择 哪个组件。



77. Intent 传递数据的方式，有大小限制 吗

​	Intent 用于 不同应用进程 或 同个应用中 不同组件（比如 activity、Service） 传递数据



​	传递 基本数据类型**int、float 和 数组**，集合，都通过 **putExtra( )方法，传入键值对**。

​	也可以传递 对象引用类型，但是先要 序列化， 实现 Serializable 或 Parcelable 序列化接口，重写方法。

​	Intent 传递的数据大小 有限制，1M左右，超过的话，就会 抛异常报错。所以要使用 文件 或者  SQLite数据库，进行传输。



​	最后在activity里面， 调用 startActivity（）方法，传入intent，跳转到 另外一个 activity，传输数据。



78. Bundle 是什么，传递数据的方式，可以传递 哪些数据类型，有大小限制 吗

​	Bundle 用于 不同应用进程 或 同个应用中 不同组件（比如 activity、Service） 传递数据



​	传递 基本数据类型int、float 和 数组，集合，都是通过 putXXX( )(putInt, putString)方法，传入键值对。

​	也可以传递 对象引用类型，但是先要 序列化， 实现 Serializable 或 Parcelable 序列化接口，重写方法。

​	Bundle 传递的数据大小 有限制，1M左右，超过的话，就会 抛异常报错。所以要使用 文件 或者  SQLite数据库，进行传输。



​	最后在activity 里面，调用 startActivity（）方法，需要 先把 Bundle 放入 Intent里面，然后 再传入intent，跳转到另外一个 activity，传输数据。



79. Bundle 和Intent 的区别（有了 Intent 为什么还要用 Bundle？：Bundle与Intent的区别在于Bundle只能存储数据，Intent除了能存储数据外还能跳转activity/启动service/广播）

​	两者联系：Bundle 和 Intent 都是用于 不同应用进程 或 同个应用中 不同组件（比如 activity、Service） 传递数据。它们 可以传递 基本数据类型int、float 和 数组，集合，对象引用类型。Bundle  通常搭配 Intent  一起使用。当传递 复杂数据的时候，可以 先把数据  存入Bundle。然后再把 整个Bundle  传入intent，一起打包 传输。

​	Intent：主要用于 启动组件（比如 activity、service），比如 调用 startActivity（）   方法，传入intent ，跳转启动 其他 activity。

​	Bundle：主要用于 存储数据。



80. Bundle 传输数据的 注意点

​	在使用 Bundle 传递数据时，要根据  数据类型 调用 合适的方法，如 putInt（）、putString（）等等。

​	也可以传递 对象引用类型，但是先要 序列化， 实现 Serializable 或 Parcelable 序列化接口，重写方法。

​	Bundle 传递的数据大小 有限制，1M左右，超过的话，就会 抛异常报错。所以要使用 文件 或者  SQLite数据库，进行传输。



81. 安卓的 序列化有哪几种方式，区别，为什么 Parcelable 的效率要比 Serializable 高

先讲下 序列化的含义。

序列化：把 java对象 转为 二进制字节流

反序列化：把 二进制字节流 转为 java对象

常用于  文件传输、网络传输，传输 对象 的时候。需要先把对象  序列化成  二进制字节流，方便 传输。

序列化 主要有 2 种 方式：Serializable 和 Parcelable

Serializable：在 对象类 中，直接实现  Serializable接口 就行，不需要 重写 对应的方法。使用 反射机制，会有额外的开销，传输性能 较低。而且序列化过程中，会创建 大量临时对象，序列化后的数据，内存占用较大。

Parcelable：在 对象类 中，实现  Parcelable接口 ，需要重写 对应方法 writeToParcel（）。不使用 反射机制，没有 额外的开销，传输 性能高。而且序列化过程中，不会创建 大量临时对象，内存占用 更小。因为 专门为  Android平台 设计的，做了 特殊优化，序列化后的 数据更加紧凑。

所以 序列化的时候，推荐使用 Parcelable 方式。



## 持久化

82. 安卓的 持久化方式 有哪些

​	文件存储：主要是 存储  文本、图片、视频等等。

​	SharedPreferences：使用  XML  文件形式，存储 键值对 数据，主要是存储 配置信息。

​	SQLite 数据库：是一个 轻量级 数据库。主要 就是  创建库，创建表，支持 sql语句 进行 CRUD 操作。



83. SharedPreferences 是什么

​	SharedPreferences 使用  XML  文件形式，存储 键值对 数据，主要是存储 配置信息。

​	主要是获取 Edit对象，调用 putXXX（）方法，添加数据。最后调用 apply（）方法，就能写入 文件。



84. SharedPreferences 的  commit( )和apply( ) 的区别

​	commit（）方法：是同步阻塞 线程，性能比较 低。有 返回值，如果 写入成功，那么会 返回true。

​	apply（）方法：是异步非阻塞 当前线程，性能比较高。没有 返回值。

​	尽量采用 apply（）方法 而不是 commit( ) 方法。因为 不会阻塞 当前线程，性能 高。只有需要 立即确认，数据是否  写入 成功 时，才 使用 commit() 方法。



85. SharedPreferences 使用的注意点

​	要使用 MODE_PRIVATE  私有模式，这样子 写入的数据，只有 当前应用本身 可见，防止  其他应用 访问，提高 安全性。

​	尽量使用 apply( ) 方法 而不是 commit( ) 方法来 提交数据。因为 apply( ) 是异步的，不会阻塞 主线程，性能 高。只有在 需要 立即确认，数据是否  写入 成功 时，才 使用 commit() 方法。



86. SQLite数据库 是什么

​	SQLite 数据库：是一个 轻量级 数据库。主要 就是  创建库，创建表，支持 sql语句 进行 CRUD 操作。

​	先定义一个类， 继承 SQLiteOpenHelper类，重写 onCreate( )方法 ，使用  sql语句 初始化 创建表 。

​	然后 初始化这个 类，调用 getWritableDatabase（）方法，获得一个 db对象，它提供了 CRUD方法，然后就能 插入和查询 数据了。

## Kotlin

87. kotlin相比java的区别，好处

​	kotlin 定义变量的时候，要用 var 或者 val 关键字去修饰。然后 变量名 在前面，中间用 冒号： 隔开，数据类型 在后面，比如 var a : Int = 10 。Java的话，数据类型 在前面，变量名 在后面。

​	kotlin的常量 要用val 修饰，不可变。Java要用 final 修饰。

​	kotlin 的方法 还支持 默认参数 。Java不支持。

​	kotlin 还提供了 扩展函数，比如 apply、also 等等，简化了 代码。Java就不支持。



88. kotlin的高阶的扩展函数了解吗，比如  apply和also  的区别

​	apply、also即是高阶函数又是扩展函数；

**apply：**

​	对于  任何类型  的对象，都可以调用  .apply { }  。使用 this  作为 对象的引用 。在它的花括号里面，可以直接访问 当前对象的 所有 属性和方法。只需要 直接  属性名 = 进行赋值，或者  方法名( ) 。 不需要写出  this. 这个前缀了，简化 代码。

​	它的返回值就是，当前对象。

​	常常用于  某个对象的 初始化。

**also：**

​	和 apply 扩展函数 差不多，最后返回的也是 当前对象，也适用于 对象的 初始化。

​	但是 also 使用  it 作为 对象的引用，在它的花括号里面，要显示使用  it.属性名 或者方法名( ) 。

完全遵照你提供的“八股文”格式，为你整理出 `let`、`run`、`with` 的完整定义。这三兄弟的返回值都**不再是当前对象**，这是它们与 `apply`、`also` 最本质的区别。

---

**let：**

​	对于 **任何类型** 的对象，都可以调用 `.let { }`。它使用 **`it`** 作为对象的引用（默认参数名，也可以自定义命名）。在它的花括号里面，要显式使用 `it.属性名` 或者 `it.方法名( )` 来操作当前对象。

​	它的返回值是 **Lambda 表达式中的最后一行代码的结果**（可以是任意类型），**不是** 当前对象本身。

​	常常用于 **判空处理**（结合安全调用符 `?.`，只在非空时执行）和 **数据映射转换**（将当前对象处理成另一个新类型的值）。

---

**run：**

​	对于 **任何类型** 的对象，都可以调用 `.run { }`。它使用 **`this`** 作为对象的引用。在它的花括号里面，可以直接访问当前对象的所有属性和方法。只需要直接 `属性名 = 进行赋值`，或者 `方法名( )`，不需要写出 `this.` 这个前缀。

​	它的返回值是 **Lambda 表达式中的最后一行代码的结果**（可以是任意类型），**不是** 当前对象本身。

​	常常用于 **在对象已经存在的情况下，既要执行一系列配置，又要计算出一个最终结果**（例如：根据 View 的宽高计算得出一个布尔值，或结合可空对象执行逻辑并返回处理值）。

---

**with：**

它 **不是** 扩展函数，而是一个顶层函数，需要将当前对象作为**普通参数**传入：`with(对象) { }`。它使用 **`this`** 作为对象的引用。在它的花括号里面，可以直接访问当前对象的所有属性和方法。只需要直接 `属性名 = 进行赋值`，或者 `方法名( )`，不需要写出 `this.` 这个前缀（写法上与 `apply` 和 `run` 内部完全一致）。

它的返回值是 **Lambda 表达式中的最后一行代码的结果**（可以是任意类型），**不是** 当前对象本身。

常常用于 **在非链式调用的普通代码块中，连续多次操作同一个对象**（例如在 Activity 的 `onCreate` 中对同一个 `TextView` 进行多次文本、颜色、大小设置），或者配合建造者模式（Builder）使用，可以让代码块看起来像一个独立的配置作用域。



89. kotlin协程 了解吗

​	一个进程 里面 有多个 线程。 一个线程 里面 有多个 协程。

​	线程 的上下文切换 开销较大。协程 的上下文切换开销较小。程是 轻量级 线程。

​	多个协程 可以 并发执行，主要用于 异步并发编程的。

​	kotlin 的代码层面，怎么启动 协程 ：可以使用 GlobalScope .launch { }  启动  一个协程。

​	协程的挂起：在同一个 线程 里面，如果你启动 多个 协程，没用 挂起操作，这些协程  就会   从上到下  顺序执行。如果 调用 delay（）方法，就可以让 某个协程 挂起 一段时间，然后 其他协程 就会执行，就能实现 同一线程的 多个 协程的 并发异步 执行。



90. kotlin 没有 static 关键字，怎么创建  静态方法

使用 object 关键字 ，定义一个类，其实是个 单例类，里面定义 属性 和方法名  。  然后 在其他地方，就可以直接   类名. 方法名。

在 当前类 里面，使用  companion object 创建一个 伴生对象，里面定义 属性  和  方法名。然后 在其他地方， 就可以直接  当前类名.方法名。



## 杂谈

91. 你对安卓的源码 哪块比较熟悉，展开说说

​	我对 View的绘制流程比较熟悉。

​	view 的绘制流程，分为 3 个 步骤，测量、布局、绘制 。

​	测量：主要是测量 视图的大小。每个视图 都会 被调用 measure（）方法，会接收 2 个参数，分别是 宽高的测量规范MeasureSpec，它们是 父视图 计算好的，传过来 的 。然后就会调用 重写过的 onMeasure（）方法，测量出 视图的 最终大小。如果 当前视图 是 ViewGroup，会一直 循环遍历 子视图 ， 先测量 出 所有 子视图 的大小，累加 或者 比较 之后，才知道 自己的大小。

​	布局：主要是确定 子视图在 父视图中的 位置。每个视图都会被调用 layout（）方法，会接收 4 个 参数，left、top、right、bottom，相对于 父视图的 各个边界距离  。 然后就会调用 重写过的 onLayout（）方法，确定 视图的 最终位置。如果  当前视图  是 ViewGroup，先确定 自己的布局，然后再 一直 循环遍历 子视图 ，才确定 子视图的 位置。

​	绘制：主要是把 视图 绘制到 屏幕上。每个视图都会被调用 draw( )方法， 接收一个 Canvas 参数，方法内部 也可以使用 Paint画笔，用于 绘制形状、颜色、背景。然后会调用 重写过的 onDraw( ) 方法，进行 绘制。如果 当前视图 是 ViewGroup，先绘制 自己，再一直 循环遍历 子视图，才绘制 子视图。



92. MVC、MVP、MVVM 的区别

MVC：

​	Model 层：数据源，比如 文件、网络请求、数据库。

​	Controller 层：调用 Model层的数据，处理 业务逻辑，**通知**  View更新。

​	View层：负责 显示 UI，接收 用户的输入。

​	特点就是，比如 Activity或fragment 要作为 Controller 和 View层（既V又C），又要 调用Model层的数据，又要 更新View ，代码太沉重了，耦合度 太高了。

MVP：

​	特有的 Presenter层：作为中间人，作为 Model层 和 View层的 桥梁。负责 调用 Model层的数据，处理 业务逻辑，通知 更新 View。

​	特点就是，比如   Activity或fragment  只需要 作为 View 层，然后  只负责 更新View  就行，不需要 调用 Model层，内部插入一个 Presenter，传入 当前的this，可以 解耦。缺点就是，项目中  会有  很多 接口。

MVVM：

​	特有的 ViewModel层：通过 数据绑定技术，当数据 发生改变，可以 自动  通知更新 View。负责 调用 Model层的数据，处理 业务逻辑。

​	特点就是，比如  Activity或fragment  只需要作为  View层，可以 自动更新 View ，大大减少了 手动更新 View 的代码，进一步 解耦。



93. 安卓发生内存泄漏的情况，怎么解决，如何定位排查

​	内存泄露 就是指，不再使用的 对象，无法被 GC垃圾回收，就会 浪费内存 ，可能会 导致 OOM。

发生场景：

- 单例类 和 静态变量：它们的生命周期  都是 很久的，一直 存在于 内存中，直到 应用程序 结束。如果 它们 持有对 Activity 的引用，当acitivity 要销毁 的时候，activity 就无法 被 GC垃圾回收 。 解决方法，使用 弱引用 。或者 在activity 要销毁的时候，把引用 置空 null 。

- 在 Activity 中用 **匿名**内部类或者**非静态**内部类创建handler时，会默认（内部类对象会隐式）持有 外部activity 的引用。当 activity 要销毁的时候，但是 Handler 还在处理消息，仍然持有 对activity的引用，activty就不能被GC 垃圾回收，会造成 内存泄漏。解决方法，使用 静态内部类不加 inner，相当于 Java static class） ，这样默认就不持有 activity的引用了， **同时** 使用 弱引用。 或者在 activity 要销毁的时候，调用一下 removeCallbacksAndMessages（）方法，移除当前 所有消息（断开消息队列对 Handler 以及 Activity 的引用链，使 Activity 在没有其他强引用时可以被 GC 回收。）。

**在 Kotlin 中，类似 Java 静态内部类的写法，就是去掉 `inner`。**

但是 Kotlin 里面严格来说叫：

- **不带 `inner` 的嵌套类（nested class）**
- 它的行为类似 Java 的 `static class`

####  匿名内部类创建 Handler（会持有 Activity 引用）

```kotlin
class MainActivity : AppCompatActivity() {

    private val handler = object : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)

            // 访问 Activity 的 UI
            textView.text = "收到消息"
        }
    }

}
```

#### 非静态内部类 Handler

```kotlin
class MainActivity : AppCompatActivity() {


    private val handler = MyHandler()


    inner class MyHandler : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: Message) {

            textView.text = "更新"

        }
    }

}
```

- 打开了 文件 等资源对象 记得关闭，调用 close（）方法。

- threadLocal 的底层是 ThreadLocalMap，key 是ThreadLocal的 弱引用，value是强引用，所以GC垃圾回收的时候，key 会被 清理掉，value 不会清理，导致 内存泄漏，所以使用完，记得调用 remove( )方法。

如何定位 ：

- 使用 Android Studio 编译器，自带的 Android Profiler 工具：可以实时显示 应用的内存 使用情况。如果内存占用  持续上升 而不下降，这可能是 内存泄漏，可以生成 内存快照，进行 定位分析。

- 也可以在 项目中导入 LeakCanary，在activity的 onCreate( )生命周期函数 里面， 调用 install( )方法  传入当前 this，初始化  LeakCanary对象。如果发生了 内存泄漏，就会有一个  弹窗 ， 跳转到 另外一个界面，会有 详细的 分析报告。
- 

94. 内存抖动是什么，怎么解决，如何定位排查

​	内存 抖动是指，频繁地 创建对象，频繁地 分配和释放 内存，会导致 GC垃圾回收 频繁运行，从而影响 应用程序的 性能。

解决：

​	避免 在for循环体中 频繁创建 对象，尽量 重复使用 对象。

如何定位 ：

​	使用 Android Studio 编译器，自带的 Android Profiler 工具：可以实时显示 应用的内存 使用情况。如果内存占用  一直波动，这可能是 内存抖动，可以生成 内存快照，进行 定位分析。

​	也可以在 项目中导入 LeakCanary，在activity的 OnCreate( )生命周期函数 里面， 调用 install( )方法  传入当前 this，初始化  LeakCanary对象，如果发生了 内存泄漏，就会有一个  弹窗 ， 跳转到 另外一个界面，会有 详细的 分析报告。



95. 内存溢出 OOM是什么，怎么解决，如何定位排查

​	内存溢出 OOM是指，内存 占用 不断上升，超过限制，导致 应用程序 崩溃。

解决：

​	尽量避免 内存泄露的一些场景。比如 使用  单例类或者 静态变量  的时候，如果 持有了 activity  的引用，可以改为 弱引用。或者在activity 要销毁的时候，把引用 置空 null。

​	避免 在for循环体中 频繁创建 对象，尽量 重复使用 对象。

​	避免  创建  大 对象，大数组。

如何定位 ：

​	使用 Android Studio 编译器，自带的 Android Profiler 工具：可以实时显示 应用的内存 使用情况。如果内存占用  持续上升 而不下降，这可能是 内存泄漏，可以生成 内存快照，进行 定位分析。

​	也可以在 项目中导入 LeakCanary，在activity的 OnCreate( )生命周期函数 里面，调用 install( )方法  传入当前 this，初始化  LeakCanary对象，如果发生了 内存泄漏，就会有一个  弹窗 ， 跳转到 另外一个界面，会有 详细的 分析报告。



96. ANR 是什么，原因，怎么解决，如何定位排查

​	ANR 是指，默认是 在主线程中 执行，主线程  阻塞了。应用程序 无响应，此时会有 一个弹窗，继续等待 还是 强制退出 应用。比如 activity 在5秒内 无响应 。Service 前台服务 20s ，后台服务 200s 没有 执行完 任务。

​	原理是 Android 主线程通过 Looper 不断从 MessageQueue 中取消息执行，如果主线程执行耗时操作或者发生阻塞，导致消息队列中的事件长时间无法处理，系统通过 AMS、InputDispatcher 等机制检测到应用没有及时响应，超过规定时间后就会触发 ANR。

解决：

​	把 耗时操作（比如  文件I/O读取，网络请求 等等） 放在 子线程 异步 执行，而不是 主线程。

如何定位：

​	查看 ANR 日志：当发生  ANR 时， 会  生成一个  traces.txt  文件。可以查询 内存、CPU、GC垃圾回收 的情况，使用率 是否正常。



97.安卓的性能优化 有哪些方式

​	主要是从 内存、卡顿。

内存：

​	尽量避免 内存泄露的一些场景。比如 使用 单例类 或者 静态变量 的时候，如果 持有了 activity  的引用，可以改为 弱引用。或者在activity 要销毁的时候，把引用 置空 null。

​	避免 在for循环体中 频繁创建 对象，尽量 重复使用 对象。

​	避免 创建 大 对象，大 数组。

​	适当 对图片 进行压缩，减少 内存占用。

卡顿：

​	把 耗时操作（比如  文件I/O读取，网络请求等等） 放在 子线程 异步 执行，而不是 主线程。

​	使用 ConstraintLayout约束布局。在复杂布局，它可以减少 多层嵌套布局 ，减少 位置计算，来提高 性能。

使用 性能分析 工具：

​	使用 Android Studio 编译器，自带的 Android Profiler 工具：可以实时显示 应用的内存 使用情况 。 如果内存占用  持续上升 而不下降，这可能是 内存泄漏，可以生成 内存快照，进行 定位分析。

​	也可以在 项目中导入 LeakCanary，在activity的 onCreate( )生命周期函数 里面， 调用 install( )方法  传入当前 this，初始化  LeakCanary对象，如果发生了 内存泄漏，就会有一个  弹窗 ， 跳转到 另外一个界面，会有 详细的 分析报告。

​	查看 ANR 日志：当发生 ANR  时， 会生成一个  traces.txt  文件。可以查询 内存、CPU、GC垃圾回收 的情况，使用率 是否正常。



98. app 的启动流程

1. 启动器向AMS请求启动activity

	2. AMS 会去检查 应用程序app 是否 已经在运行。如果 没有，那么通过 Zygote 进程，来 fork（）一个 新的进程，来运行 app。因为  Zygote 进程 预加载了 一些通用的 系统资源，可以加快  app启动速度；
	2. ActivityThread.main()，创建主线程 Looper 和消息队列；
	2. ActivityThread创建application，进行全局的初始化；
	2. ActivityThread启动目标activity；
	2. （opt）WindowManager绘制界面



99. 冷启动  和热启动  的区别，以及 怎么优化

​	冷启：是指  应用程序，从 未启动 到 启动。因为 要重新 创建 进程，重新加载 资源，启动速度 较慢。

​	热启：是指 应用程序 已经启动，从后台 恢复到 前台。因为 应用程序的 进程和大部分资源  已经在 内存中，启动速度 较快。

​	怎么优化：在  application和 acitivty 的 onCreate（）方法中，把 耗时操作，放入子线程 异步执行，而不是 主线程，加快 启动速度。



100. app之间 如何跳转

​	在 某个app应用 里面，调用 startActivity（）方法，传入intent，设置 目标app的包名  和  目标activity的类名，就能 成功跳转 app了。

​	目标 Activity 需要在 Manifest 中声明 ，允许其他应用访问。



101. 安卓进程的优先级  了解吗

​	前台进程：比如 可见，可交互的 activity。优先级 最高，如果 系统 内存不足，最晚 才杀死 前台进程。

​	可见进程：比如 可见，但不能交互 的activity 。比如  弹出了个 对话框，它底下的 activity 所在进程就是 可见进程。

​	服务进程：通过 startService( )方法  启动的 Service组件的进程。比如在 后台 长时间运行的 音乐播放服务。

​	后台进程：比如 不可见的 Activity 。比如 切换到其他应用app，之前的应用 所在的进程  就成了 后台进程。

​	空进程：不包含 任何组件（Activity，Service）的进程。优先级 最低，最先 杀死。



102. 你对 JNI 的了解，怎么 使用

​	JNI 是指 Java本地化接口。允许 **Java代码 调用  C、C++ 编写的本地代码**，进行交互。比如 调用  操作系统的底层功能，主要用于 游戏、音视频处理、性能优化 等场景。

​	怎么使用 ：先定义 Java 的 native 本地方法。然后 使用 javah工具 生成对应的 头文件  。 然后编写 C++ 代码，方法名 就是 Java_包名_类名_方法名。最后在 Java代码中，调用  System.loadLibrary( )方法，然后就可以 调用   C++编写的 本地代码了。



103. NDK 了解吗

​	NDK 开发，主要是使用  C或C++  编写 本地代码， **然后编译成 共享库（.so文件）。然后 通过 JNI，调用 这些 本地代码。**

​	比如 调用  操作系统的底层功能，主要用于 游戏、音视频处理、性能优化 等场景。





104. jetpack 了解吗，有哪些 常见的组件

jetpack 是一种 框架，提供了 一些组件，简化 开发。

Lifecycle：用于管理  四大组件（如 activity 、Service）的生命周期。

ViewModel：用于保存 UI 相关数据，避免 Activity/Fragment 因配置变化重新创建导致数据丢失。

LiveData：是一种 可观察的数据，常常配合  ViewModel 一起使用。

Room：是 SQLite数据库的 对象映射框架，可以更方便地  操作 数据库。通过定义 实体类、Dao层，数据库 层。



- Flow 是 Kotlin 协程提供的异步数据流，可以连续发送多个数据，属于冷流，需要 collect 才会开始执行。
- StateFlow 是 Flow 的一种特殊实现，用于**保存和管理 UI 状态**，它必须有初始值，并且会保存最新状态，新订阅者可以立即获取当前状态。
- LiveData 是 Android 提供的生命周期感知的数据观察组件。相比 LiveData，StateFlow 基于协程，线程控制更加灵活，更适合现代 Kotlin MVVM 架构；但是 StateFlow 本身不感知生命周期，需要结合 repeatOnLifecycle 使用。

|             | LiveData          | StateFlow             |
| ----------- | ----------------- | --------------------- |
| 来源        | Android Lifecycle | Kotlin Coroutine      |
| Compose读取 | observeAsState()  | collectAsState()      |
| 需要初始值  | 不一定            | 必须                  |
| 生命周期    | 天然支持          | 需要repeatOnLifecycle |
| 推荐程度    | 旧项目较多        | 新项目推荐            |

Compose 是声明式 UI，核心是状态驱动界面。StateFlow 本身就是 Kotlin 协程中的状态容器，可以很好地表示 UI 状态，并通过 collectAsState 或 collectAsStateWithLifecycle 转换成 Compose State，触发重组。同时 Android 现在大量使用 Kotlin 协程，Flow 生态可以直接完成数据转换、组合和线程切换。而 LiveData 主要为传统 View 系统设计，虽然 Compose 可以通过 observeAsState 使用，但需要额外适配，功能和协程生态结合不如 StateFlow。

105. jetpack compose 了解吗

​	Jetpack Compose 是 Google 提供的声明式 UI 框架，用 Kotlin 代码代替传统 XML 布局来构建界面。Compose 通过状态驱动 UI，当 LiveData、StateFlow 等数据源变化时，将数据转换为 Compose State，触发 Recomposition（重新组合），自动更新 UI。

​	相比传统 XML + View 的方式，Compose 减少了大量模板代码，开发效率更高，同时更加适合 MVVM 架构和响应式开发。



**“Compose为什么性能好？”**

> Compose通过智能重组机制，只重新执行受影响的Composable函数，传统View采用命令式更新，需要开发者主动管理UI变化；如果更新范围控制不好，容易产生额外的View操作。而Compose通过状态追踪，可以更细粒度地进行重组。因此**减少了不必要的UI更新**。







106. 大前端，跨端了解吗

大前端 就是 跨端，是指，只使用 一种 开发语言，就可以 开发一个 应用程序app，它可以 在多个系统 运行（比如 Android、IOS）。好处就是 ，简化 开发，提高 开发效率。缺点 就是，需要考虑  不同平台的性能 以及 兼容性 问题。

React Native 使用 React 和 JavaScript/TypeScript 描述界面，并将组件渲染为 Android、iOS 的原生 UI。旧架构通过异步 Bridge 在 JavaScript 与原生层之间通信，频繁通信时可能产生性能瓶颈；新架构使用 JSI、Fabric 和 TurboModules，减少了桥接与序列化开销。其性能通常能满足普通应用需求，但在复杂动画、密集计算或高频跨层通信场景下，仍可能不如针对平台深度优化的原生实现。

比如 Flutter，它有自己 独立的 **渲染引擎**，不依赖  原生UI组件，性能 较好。



107. 协程栈有吗？

Continuation对象保存协程信息；

```kotlin
{
    当前状态 state = 1

    局部变量:
        xxx

    下一步执行:
        funB()
}
```

