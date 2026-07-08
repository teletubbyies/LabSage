1. @Composable = 「这块函数是 UI 积木」。它是个编译器标签,让 Compose 把这个函数纳入界面构建体系。Compose 界面 = 一堆 @Composable 函数互相嵌套拼出来的。
2. @Composable中变量：

第一类:普通 val(你 LabSageRoot 里那些)——「草稿纸」,用完就扔

@Composable                                                                                                                                                                                                                                                                                                               
fun LabSageRoot() {                                                                                                                                                                                                                                                                                                       
val app = ...                        // ← 这些都是普通局部变量                                                                                                                                                                                                                                                        
val settings by app.appSettings...   // ← 每次重新调用都重算一遍                                                                                                                                                                                                                                                      
val systemDark = isSystemInDarkTheme()                                                                                                                                                                                                                                                                                
val darkTheme = when (settings.themeMode) { ... }                                                                                                                                                                                                                                                                     
}

这些变量根本没被「存」起来。它们就像草稿纸上的演算:函数一跑就写出来,函数一结束就扔掉。下次 Compose 重新调用 LabSageRoot() 时,重新算一遍,产生全新的 val。

第二类:需要「记住」的状态 ——「便利贴」,存进 Compose 的记事本

有些东西不能每次重算,比如:输入框里打到一半的字、列表滚到的位置、导航控制器。这些必须用 remember 告诉 Compose「帮我留着」:

@Composable                                                                                                                                                                                                                                                                                                               
fun LabSageNavGraph() {                                                                                                                                                                                                                                                                                                   
val navController = rememberNavController()   // ← remember!跨重画保留                                                                                                                                                                                                                                                
...                                                                                                                                                                                                                                                                                                                   
}

这种被 remember 的东西,才会被存进 Compose 内部一个叫 Slot Table(位置表) 的数据结构里。

3. 
问题二:自动重新调用 LabSageRoot() 怎么做到的?

核心机制:观察 + 订阅(reactive / 响应式)。

一句话原理

Compose 会盯着被读取过的 State 对象。一旦某个 State 的值变了,它就自动重新调用之前读过这个 State 的 @Composable 函数。

用你代码里的「换主题」走一遍完整流程

① 用户在设置页点了「深色主题」                                                                                                                                                                                                                                                                                            
↓                                                                                                                                                                                                                                                                                                                 
② SettingsRepository 把新设置写进 DataStore(存磁盘)                                                                                                                                                                                                                                                                       
↓                                                                                                                                                                                                                                                                                                                 
③ appSettings (StateFlow) 推出一个新值                                                                                                                                                                                                                                                                                    
└─ 这个 StateFlow 就是 LabSageApplication 里 by lazy 的那个单例!                                                                                                                                                                                                                                                       
↓                                                                                                                                                                                                                                                                                                                 
④ LabSageRoot 里的 collectAsStateWithLifecycle 收到新值                                                                                                                                                                                                                                                                   
└─ 它把 Flow 变成一个 Compose 能盯着的 State 对象                                                                                                                                                                                                                                                                      
└─ 之前读 settings 时,Compose 已经记下:                                                                                                                                                                                                                                                                                
「LabSageRoot 依赖这个 State」                                                                                                                                                                                                                                                                                      
↓                                                                                                                                                                                                                                                                                                                 
⑤ Compose 发现 State 变了 → 标记 LabSageRoot 「需要重画」                                                                                                                                                                                                                                                                 
↓                                                                                                                                                                                                                                                                                                                 
⑥ Compose 重新调用 LabSageRoot()                                                                                                                                                                                                                                                                                          
↓                                                                                                                                                                                                                                                                                                                 
⑦ settings.themeMode 变了 → darkTheme 重算成 true                                                                                                                                                                                                                                                                         
↓                                                                                                                                                                                                                                                                                                                 
⑧ LabSageTheme(darkTheme = true) → 界面立刻变深色 🎉

关键的两步「魔法」

魔法一:读取时自动登记依赖                                                                                                                                                                                                                                                                                                 
当你写 val settings by ...state... 然后用到 settings 时,Compose 在背后悄悄记一笔:「这个函数读了这个 State」。你不用手动写任何监听代码。

魔法二:变化时自动重画                                                                                                                                                                                                                                                                                                     
当 State 的值改变,Compose 查它的「依赖登记本」,找到所有读过这个 State 的函数,重新调用它们。

4. by
   `by` 是 Kotlin 的**「属性委托」(property delegation)**关键字。一句话:

> **`by` = 「我不自己存这个值,找个代理(delegate)帮我管,我要用时去问它要」。**

## 打个比方:雇个会计 🧑‍💼

- **不用 `by`**:你自己买个保险箱存钱,每次花钱自己开箱取(`写 .value`)
- **用 `by`**:你雇个会计(代理),需要钱就跟他说一声,他帮你从箱子里拿。你**感觉钱就在手边**,其实是会计帮你转了一手

`val settings by 代理` 的意思是:**「settings 自己不存值,每次读它,实际是去问代理要」**。

## 背后其实是语法糖

```kotlin
val settings by delegate
```
等价于偷偷变成:
```kotlin
// 每次你读 settings,编译器帮你调 delegate.getValue()
val settingsValue = delegate.getValue(...)
```

所以 `settings` 没有自己的存储空间,它是个「转发器」—— 读取请求转发给代理。

## 你代码里的两种 `by`,其实是**同一个机制**,只是代理不同

这一点很关键,能一下子串起来:

| 写法                    | 代理是谁     | 读它时实际发生什么                      |
| ----------------------- | ------------ | --------------------------------------- |
| `val x by lazy { ... }` | `Lazy` 对象  | 第一次读→执行块、存结果;以后读→返回缓存 |
| `val settings by state` | `State` 对象 | 每次读→返回 `state.value`(最新值)       |

**`by` 还是那个 `by`,变的只是后面接的代理。** `lazy{}` 和 `collectAsStateWithLifecycle()` 返回的 `State`,都是符合「代理」规格的东西。

## 为什么 `by` 对 Compose 特别重要?—— 接上一题的魔法 ✨

还记得问「怎么自动重新调用」吗?我说 Compose 是在**读取 `State`** 时偷偷登记依赖的。而 `by` 正是让这个登记发生的桥梁:

```kotlin
val settings by app.appSettings.collectAsStateWithLifecycle()
//            └by┘ └────── 返回一个 State<AppSettings> ──────┘

// 然后你这样用:
when (settings.themeMode) { ... }
//         ↑ 每次读 settings,背后都在调 state.value
//         ↑ 而 Compose 就是在「读 state.value」这个动作上挂了监听!
```

**因为 `by` 把每次读取都路由到了 `state.value`,所以 Compose 才能捕捉到「这个函数读了这个 State」,从而在 State 变化时重新调用它。** 没 `by` 就没这个自动挂钩。

## 对比:不用 `by` 长什么样?

```kotlin
// 不用 by —— 自己存 State,每次手动 .value
val settingsState = app.appSettings.collectAsStateWithLifecycle()
when (settingsState.value.themeMode) { ... }   // ← 到处写 .value,啰嗦

// 用 by —— 把 .value 这步外包给代理
val settings by app.appSettings.collectAsStateWithLifecycle()
when (settings.themeMode) { ... }              // ← 干净,像普通变量
```

两种都能跑、效果一样(观察/重画都正常),`by` 纯粹是**让你少写 `.value`,把状态当普通变量用**。

## 一句话总结

> `by` = **属性委托**,把「读/写这个变量」的活儿外包给一个代理对象。
> - `by lazy {}` → 代理是 Lazy(算一次、缓存)
> - `val settings by State` → 代理是 State(每次读拿最新值,顺带让 Compose 盯上它)
>
> 它是 `by lazy` 和 Compose 状态读取**背后同一个机制**,只是换了不同的代理。

你现在已经把「单例 → `by lazy` → `by` 委托 → Compose 状态观察 → 自动重画」整条链路打通了 🎉

5. 
思路**方向对了,但两个地方需要精确化**,我帮你修正一下,这能让你后面少踩坑。

## 修正一:盯梢的不是 `setContent`,而是它「启动的引擎」

更准确的说法:

- `setContent { ... }` = **打开 Compose 世界、启动那套「自动重画引擎」**(具体叫 **Recomposer + Composition + 快照系统**)
- 真正**盯着 State 变没变、安排重画**的,是这套引擎(尤其 **Recomposer 监工**),不是 `setContent` 本身

类比:
> `setContent` = 开了一家「自动重画工厂」(把流水线装好)。
> **Recomposer** = 工厂里的监工,真正在盯着 State、决定谁要重画。
> 没有 `setContent` 把工厂开起来,`@Composable` 就只是普通函数,`State` 也没地方登记依赖;但「盯梢」这个动作是监工(Recomposer)干的。

所以「`setContent` 监督 State」这句话,更精确的说法是:**`setContent` 启动了会监督 State 的引擎**。

## 修正二(更重要):不是「整个作用域重画」,而是**精准只重画读了那个 State 的函数** 🎯

这是新手最大的误解,一定要掰过来:

> Compose **不会**因为一个 State 变了,就把 `setContent` 里整棵树全部重新跑一遍。它只重新调用**真正读过这个变了 State 的那些 `@Composable` 函数**。

举例,假设你 `setContent` 里是这样:

```kotlin
setContent {
    LabSageRoot()          // 读了 settings
    SomeOtherThing()       // ❌ 没读 settings
}
```

当 `settings` 变了:
- ✅ `LabSageRoot()` 会被重新调用(因为它读过 `settings`)
- ❌ `SomeOtherThing()` **不会被重新调用**(它没读 `settings`,跟它无关)

这叫 **精准重组(smart recomposition / 跳过)**,是 Compose 性能的关键 —— 哪怕界面有一百块,只重画「读了变了 State 的那一两块」,其余原封不动。

**回到你 `LabSageRoot` 的真实情况:**

```kotlin
@Composable
fun LabSageRoot() {
    val settings by app.appSettings.collectAsStateWithLifecycle()  // 读 State
    ...
}
```
`settings` 变 → 只有 `LabSageRoot` 这一块(以及它下游用到了新值的部分)重画,别的界面纹丝不动。

## 修正三:能触发重画的,只有 `State` 对象,不是「任何变量」

接上题的「草稿纸 vs 便利贴」:

- 普通 `val`(如 `val darkTheme = ...`)变了?**它根本不会自己「变」** —— 它是每次重画时重算的临时草稿,不在观察名单里
- 只有 **`State` 对象**(`mutableStateOf`、`collectAsStateXxx`、`derivedStateOf` 产生的)被改写时,监工才会收到通知

所以触发链是:**`State` 被写入** → 监工(Recomposer)收到通知 → 查依赖登记本 → 找到读过它的 composable → 重新调用它们。普通变量不参与。

## 修正后的完整心智模型

```
setContent { LabSageRoot() }
     │
     └─ 启动「自动重画引擎」(Recomposer + Composition + 快照系统)
                                        │
                                        │  ① 初次运行时,读到哪个 State,
                                        │     就在登记本(slot table)记一笔:
                                        │     「LabSageRoot 依赖这个 State」
                                        ▼
        某个 State 被写入新值 ──────►  监工(Recomposer)察觉
                                        │
                                        │  ② 查登记本:谁读过这个 State?
                                        │     → 只有 LabSageRoot
                                        ▼
                                  ③ 只重新调用 LabSageRoot()
                                  (别没读它的函数一律不动)
```

## 一句话总结

> - `setContent` 是**启动**那套监督引擎,真正「盯梢 + 重画」的是 **Recomposer**(监工)。
> - 而且它**很精准**:State 变了,只重画**读过它的那些函数**,不是整个 `setContent` 树。
> - 触发源只有 **`State` 对象**,普通 `val` 不算。

你原来的理解抓到了「响应式」的大方向,只是把「引擎」和「作用域整体重画」这两点精确化一下就更准了 👍 

5. 
- mutableStateOf(...) = 「让 Compose 盯梢这个值」(可观察)
- remember { } = 「让括号里的东西跨重组保留」(不丢)
- 组合起来 = 一个「改了能刷新、又不会丢」的本地状态

6. 延迟初始化使用的是lateinit关键字，它可以告诉Kotlin编译器，我会在晚些时候对这个变量进行初始化，这样就不用在一开始的时候将它赋值为null了。
7. sealed class(密封类)：当在when语句中传入一个密封类变量作为条件时，Kotlin编译器会自动检查该密封类有哪些子类，并强制要求你将每一个子类所对应的条件全部处理。
   - 密封类及其所有子类只能定义在同一个文件的顶层位置，不能嵌套在其他类中，这是被密封类底层的实现机制所限制的。
8. **高阶函数。**如果一个函数接收另一个函数作为参数，或者返回值的类型是另一个函数，那么该函数就称为高阶函数
   - 如果没有返回值就使用Unit；
   - 高阶函数允许让函数类型的参数来决定函数的执行逻辑。即使是同一个高阶函数，只要传入不同的函数类型参数，那么它的执行逻辑和最终的返回结果就可能是完全不同的。
   - Kotlin还支持其他多种方式来调用高阶函数，比如Lambda表达式、匿名函数、成员引用等。其中，Lambda表达式是最常见也是最普遍的高阶函数调用方式
   - 在函数类型的前面加上ClassName. 就表示这个函数类型是定义在哪个类当中的。这样就会拥有该类的上下文。
9. Kotlin提供了内联函数的功能，它可以将使用Lambda表达式带来的运行时开销完全消除。
   - 工作原理：Kotlin编译器会将内联函数中的代码在编译的时候自动替换到调用它的地方，这样也就不存在运行时的开销了
10. 为什么Kotlin还要提供一个noinline关键字来排除内联功能呢？这是因为内联的函数类型参数在编译的时候会被进行代码替换，因此它没有真正的参数属性。非内联的函数类型参数可以自由地传递给其他任何函数，因为它就是一个真实的参数，而内联的函数类型参数只允许传递给另外一个内联函数，这也是它最大的局限性。
11. 类委托
- 委托是一种设计模式，它的基本理念是：操作对象自己不会去处理某段逻辑，而是会把工作委托给另外一个辅助对象去处理。
- 类委托，它的核心思想在于将一个类的具体实现委托给另一个类去完成
  - 这种写法的好处是什么呢？既然都是调用辅助对象的方法实现，那还不如直接使用辅助对象得了。这么说确实没错，但如果我们**只是让大部分的方法实现调用辅助对象中的方法，少部分的方法实现由自己来重写，甚至加入一些自己独有的方法，那么MySet就会成为一个全新的数据结构类，这就是委托模式的意义所在。**
  - Kotlin中委托使用的关键字是by，我们只需要在接口声明的后面使用by关键字，再接上受委托的辅助对象，就可以免去之前所写的一大堆模板式的代码了，如下所示：
    class MySet<T>(val helperSet: HashSet<T>) : Set<T> by helperSet { }
  - 要分清这里其实做了**两件事**,一个是构造,一个是委托,别混在一起看:
```kotlin
class MySet<T>(val helperSet: HashSet<T>) : Set<T> by helperSet {
//     └─① 构造函数:必须传一个 HashSet<T> 进来        └─② 委托:把 Set<T> 的方法转交给它
}
```

## ① 构造:确实靠传入 HashSet 实例化

```kotlin
val s = MySet(hashSetOf(1, 2, 3))
```

主构造函数的参数 `helperSet: HashSet<T>` 被存成属性(`val`),实例化时你必须给它一个现成的 `HashSet`。

## ② `by helperSet`:这才是这行代码的真正价值——类委托(class delegation)

`Set<T> by helperSet` 的意思是:`MySet` 声称自己实现了 `Set<T>` 接口,但**所有接口方法的实现都自动转交给 `helperSet`**,编译器替你生成了样板代码。等价于:

```kotlin
class MySet<T>(val helperSet: HashSet<T>) : Set<T> {
    override val size: Int get() = helperSet.size
    override fun contains(element: T) = helperSet.contains(element)
    override fun iterator(): Iterator<T> = helperSet.iterator()
    override fun isEmpty() = helperSet.isEmpty()
    // ... Set 接口里的所有方法,全部一行行转发给 helperSet
}
```

## 为什么不用继承?——为了"装饰"而不是"是"

`HashSet` 是 `MutableSet` 的实现,如果直接 `class MySet : HashSet<T>()` 那是继承(强耦合,且会暴露所有可变方法)。而 `by` 让你可以:

- 只暴露 `Set<T>`(只读接口),把可变性藏起来;
- **选择性重写**某几个方法,其余照常委托:

```kotlin
class MySet<T>(val helperSet: HashSet<T>) : Set<T> by helperSet {
    override fun contains(element: T): Boolean {
        println("查了一下")           // 自己实现
        return helperSet.contains(element)
    }
    // 其它 add / size / iterator ... 依旧自动走 helperSet
}
```

## 一句话

构造上,你说的没错——实例就是靠传进来的那个 `HashSet` 撑起来的;但语法上的核心是 `by` 关键字带来的**委托**,让 `MySet` 把 `Set<T>` 的实现整体外包给那个 `helperSet`,你只需要重写关心的方法。这就是"组合优先于继承"在 Kotlin 里的省写法。
12. 委托属性
- 委托属性的核心思想是将一个属性（字段）的具体实现委托给另一个类去完成。
13. ViewModel
- 存放与界面相关的数据
14.  .
     好，那我先铺垫几个最基础的概念，再逐种模式讲透。这些概念是理解"为什么"的地基，不然后面的每一条都会像凭空规定。

---

# 第一部分：先搞懂 3 个地基概念

## 1. 协程是什么？`suspend` 又是什么？

把协程想成**一个可以"按暂停键"的任务**。

打个比方：你让一个人去烧水。如果是普通函数，他会**死等**在厨房盯着水壶，啥也不干，直到水开才回来交差——这就是"阻塞"。如果是协程，他可以对系统说"水要 5 分钟才开，**我先暂停在这里**，期间你去干别的，水开了再叫我回来继续"——这个"暂停点"就是 `suspend`。

```kotlin
suspend fun saveUserMessage(...)   // 这个函数会"暂停"等数据库写完
```

`suspend` 函数的特点：**它可能在执行中暂停、把线程让给别人用、之后再恢复**。所以它只能在协程里调用，不能在普通函数里直接调。

## 2. 主线程是什么？为什么不能卡它？

Android 有一个"主线程"（也叫 UI 线程），它负责两件事：**画屏幕** + **响应你的点击滑动**。

主线程是**单线程**，一次只能做一件事。如果你让它在主线程上去做"写数据库"这种耗时操作，它就没空画屏幕了——屏幕卡住，点按钮没反应。卡超过 5 秒，系统会弹一个**"应用无响应"（ANR）**对话框，用户会觉得 app 崩了。

所以铁律是：**耗时操作（网络、磁盘读写、大量计算）绝对不能在主线程跑。**

## 3. `viewModelScope` 是什么？为什么用它？

`viewModelScope` 是 ViewModel 自带的一个"协程容器"（scope）。你 `viewModelScope.launch { ... }` 就是"在这个容器里启动一个协程"。

它有两个核心特性，**这是理解后面所有模式的关键**：

**特性一：默认在主线程跑。**
`launch { }` 块里的代码，默认就在主线程执行。这意味着你可以在里面直接更新 UI 状态（比如 `_messages.value = ...`），不用切线程。但也意味着——如果你在里面调一个不会自动切线程的耗时操作，就会卡住主线程。

**特性二：跟 ViewModel 同生共死。**
当你离开一个页面，对应的 ViewModel 会被销毁（调用 `onCleared()`）。这时 `viewModelScope` 会**自动取消里面所有正在跑的协程**。

为什么这很重要？假设没有这个机制，你发了一条消息、SSE 流正在后台收数据，这时你退出页面——协程还在跑，它会去更新一个已经不存在的 UI，要么报错、要么内存泄漏。有了 `viewModelScope`，页面一关，所有任务自动停，干净利落。

> 顺便回答一个你可能想问的：**为什么不直接用 `GlobalScope`（全局容器）？** 因为全局容器活得比页面长，页面销毁了协程还在跑，就泄漏了。`viewModelScope` 就是用来"限定寿命"的。

---

有了这三块地基，下面 5 种模式就好懂了。每种我都会讲：**它解决什么问题 → 代码 → 为什么这样写 → 不这样写会怎样**。

---

# 第二部分：5 种模式详解

## 模式 A：Fire-and-forget 写操作（"派出去就不管了"）

### 解决什么问题

用户点了个按钮，比如"删除会话"。删除是写数据库，是 `suspend` 函数。但按钮的点击回调（`onClick`）是个普通函数，不能是 `suspend`。怎么在普通函数里调 `suspend` 函数？

### 代码

```kotlin
// ConversationListViewModel.kt:45
fun deleteConversation(id: String) {           // ← 普通函数，UI 直接调
    viewModelScope.launch {                    // ← 包一层协程
        repository.deleteConversation(id)      // ← suspend 调用放进去
    }
}
```

### 为什么这么写

1. **`launch` 把 `suspend` 包起来**，这样外层的 `fun` 就能保持普通签名，UI 调用方不用关心协程。
2. **不保存返回的 Job**：注意没写成 `val job = viewModelScope.launch {...}`。因为这种操作"派出去就完事"——删了就删了，不需要中途取消它。即使用户立刻退出页面，让删除跑完反而是你想要的（总不能点了删除又反悔）。
3. **不用手动切线程**：你可能想"写数据库不是耗时吗？怎么不 `withContext(IO)`？"——因为 **Room 的 `suspend` 函数内部已经自己切到后台线程了**。你调它，它暂停、去后台干活、干完回主线程，全程不卡 UI。DataStore 同理。

### 不这样写会怎样

- 如果不包 `launch` 直接调：编译报错（普通函数里不能调 `suspend`）。
- 如果用 `GlobalScope`：用户退出页面后这个协程还在跑，可能去更新一个已经销毁的 UI，泄漏。
- 如果手动 `Thread { }.start()`：能跑，但要自己管线程、自己切回主线程更新 UI，麻烦且容易出错。

**一句话**：这是"触发一次写、不关心结果、不管它什么时候结束"的标准写法。项目里 11 处都是这个模式。

---

## 模式 B：长生命周期的 Flow 收集（"持续监听数据库变化"）

### 解决什么问题

聊天页面要显示消息列表。消息存在数据库里，而且会**不断变化**（用户发新消息、流式接收时内容一直在变）。怎么让 UI 一直跟着数据库的最新状态走？

Room 给你的是一个 `Flow`（数据流）。`Flow` 是"冷流"——**你不主动去 `collect`（收集）它，它就不工作**。就像订阅快递通知，你不订阅就收不到推送。

### 代码

```kotlin
// ChatViewModel.kt:123-129
init {
    viewModelScope.launch {                              // ← 启动一个协程专门收集
        repository.getMessages(conversationId)           // ← 拿到 Flow
            .conflate()                                  // ← 丢弃中间值（见下）
            .collect { entities ->                       // ← 持续监听，每次 DB 变化都回调
                _messages.value = entities.map { it.toUiState() }
            }
    }
}
```

### 为什么这么写

1. **放在 `init` 里**：ViewModel 一创建就开始监听，不需要等 UI 触发。
2. **用 `viewModelScope.launch`**：让这个监听跟 ViewModel 同寿命。退出页面 → ViewModel 销毁 → `collect` 自动停 → 不会泄漏。这正是地基概念 3 的特性二。
3. **`conflate()` 是什么**：流式接收时，数据库可能一秒钟变 50 次（每个 SSE chunk 都写一次）。但屏幕一秒只刷新 60 帧，没必要 50 次都处理。`conflate()` 的意思是"如果来不及处理，就只保留最新值，中间的丢掉"——把 50 次/秒压到约 10 次/秒，UI 还是一样顺，但 CPU 省了一大半。

### 不这样写会怎样

- 如果不在协程里 `collect`：Flow 永远不开始，UI 拿不到数据。
- 如果用 `GlobalScope`：退出页面后还在监听数据库，回调时去更新一个已死的 `_messages`，泄漏或崩溃。
- 如果不 `conflate()`：流式时一秒重组 50 次，低端机会卡。

**一句话**：这是"我要一直盯着数据库，它变我就更新 UI"的写法。需要持续监听就放 `init` + `launch`。

> 补充一个对比：项目里 `SettingsViewModel.kt:26` 的 `stateIn(viewModelScope, ...)` 是处理 Flow 的另一种姿势——不用手写 `launch + collect`，用一个操作符把冷流"加热"成 `StateFlow`，UI 直接订阅。**什么时候用哪个**：纯数据转换用 `stateIn`；收到数据还要执行额外动作（比如写另一个变量）用 `launch + collect`。这个项目两种都用，分得很清。

---

## 模式 C：持有 Job 的可取消流（"能随时叫停的长任务"）

### 解决什么问题

SSE 流式对话是个**特别长**的任务——可能跑几十秒。这期间用户可能：
- 想点"停止生成"
- 想重新生成
- 切换到另一个会话

这些情况下你都得**把正在跑的流式任务杀掉**。但模式 A、B 都没保存协程的引用，杀不掉。怎么办？

### 代码

```kotlin
// ChatViewModel.kt:108, 179
private var streamingJob: Job? = null          // ← 先准备一个"手柄"变量

streamingJob = viewModelScope.launch {         // ← 把 Job 存起来！
    repository.sendStreamMessage(conversationId, question)
        .catch { e -> handleStreamException(e) }
        .collect { event -> handleSseEvent(event) }
}
```

注意和模式 B 的唯一区别：**多了 `streamingJob =`**。

### 为什么这么写

1. **`launch` 会返回一个 `Job` 对象**，它是这个协程的"手柄"。拿到手柄，你就能 `.cancel()` 它。
2. **存进成员变量 `streamingJob`**：这样其他方法（比如"停止"按钮的处理函数）就能通过 `streamingJob?.cancel()` 随时叫停。
3. **这是全项目唯一持有 Job 的模式**：因为只有流式任务需要"中途取消"。删除、改设置那些根本不需要取消——写完就完了。

### 不这样写会怎样

- 如果不存 Job（像模式 B 那样）：用户点"停止"时你**没法取消**它，流会一直跑到后端发完为止，浪费电量和流量，还可能去更新已切走的页面。
- 如果用户连发两条消息：旧流还在跑，新流又开，两个流同时往同一个消息里写内容，数据全乱。所以每次开新流前应该先 `streamingJob?.cancel()` 停旧的——没有 Job 引用就做不到。

**一句话**：长任务要能取消，就得把 `launch` 返回的 Job 存起来。这是"可中断长任务"的写法。

---

## 模式 D：外层编排 launch 包内层（"先准备，再开流"）

### 解决什么问题

发一条消息，要做两件有**严格顺序**的事：
1. **先**把用户消息存进数据库（不然外键约束失败，回忆上一轮讲的 FK）
2. **再**开 SSE 流接收回复

而且第 2 步（开流）又是个需要取消的长任务（模式 C）。怎么把"顺序准备"和"可取消长流"组合起来？

### 代码

```kotlin
// ChatViewModel.kt:156-182
viewModelScope.launch {                          // ← 外层：做准备工作
    repository.upsertConversation(...)            //   suspend：等建会话写完
    repository.saveUserMessage(conversationId, question)  //   suspend：等存消息写完
    _streamState.value = StreamState.Streaming    //   更新状态
    // ... 重置各种累积器
    streamingJob = viewModelScope.launch { ... }  // ← 内层：开 SSE 流
}
```

### 为什么这么写——重点讲"为什么要两层 launch"

这是最绕的一种，我多花点篇幅。

**外层 launch 是干嘛的**：`upsertConversation` 和 `saveUserMessage` 都是 `suspend` 函数，必须等它们**一个个写完**才能进行下一步（不然消息还没存进去就开流，外键报错）。而 `suspend` 函数只能在协程里调，所以外层得有个 `launch`。

**那为什么不直接把内层流也顺序写在同一个协程里**，像这样？
```kotlin
viewModelScope.launch {
    repository.upsertConversation(...)
    repository.saveUserMessage(...)
    repository.sendStreamMessage(...).collect { ... }   // ← 如果这样写
}
```
有两个问题：

1. **拿不到 Job**：`collect` 是个"永远不返回"的调用（流一直跑）。如果顺序写，外层协程会**永远卡在 collect 上**，而且你没法把 `launch` 返回的 Job 存进 `streamingJob`——因为根本没有第二个 `launch`，没有 Job 可存。模式 C 说要能取消，这里就取消了不了。

2. **职责不清**：外层是"准备阶段"（短，几十毫秒就完），内层是"流式阶段"（长，几十秒）。把它们放一个协程里，准备阶段出问题会连累流式阶段，分开了更清晰。

所以正确做法是：外层 `launch` 顺序做完准备 → 启动**另一个** `launch` 跑流式 → 把内层的 Job 存进 `streamingJob` → 外层协程到此结束（它启动内层后就退场了）。

### 不这样写会怎样

- 如果不按顺序、先开流再存消息：外键约束失败，消息存不进去。
- 如果把两层合并：拿不到 Job，没法取消流式（模式 C 的问题）。

**一句话**：当"准备工作"和"长流"都需要协程、且长流要能取消时，用两层 launch——外层管顺序准备，内层管可取消的流。

---

## 模式 E：网络/IO 任务 + 显式切线程（"自己动手切到后台"）

### 解决什么问题

设置页有个"测试连接"按钮，点一下要真的发个 HTTP 请求去 ping 后端。问题是：这里用的是**原始的 OkHttp 同步调用**，不是 Room/DataStore 的 `suspend` 函数。

回忆地基概念 2 和 3：`viewModelScope.launch` 默认在主线程跑。OkHttp 的 `execute()` 是同步阻塞的——它不会自己切线程。如果直接在 `launch` 里调，**主线程会被卡死**直到请求返回或超时，期间屏幕冻住。

### 代码

```kotlin
// SettingsViewModel.kt:70-75
fun testConnection(url: String) {
    viewModelScope.launch {
        _connectionState.value = ConnectionState.Testing       // ← 先显示"测试中"
        _connectionState.value = withContext(Dispatchers.IO) { // ← 关键：切到后台线程
            ping(url)                                           //   在后台跑网络请求
        }                                                       // ← 自动切回主线程
    }
}
```

### 为什么这么写

1. **`withContext(Dispatchers.IO) { ... }`**：意思是"**临时切到 IO 后台线程池执行这段代码，执行完再切回主线程**"。`ping()` 在后台跑，不卡主线程；跑完拿到结果，自动回到主线程，于是你可以安全地把结果赋给 `_connectionState.value`（更新 UI 状态必须在主线程）。
2. **为什么模式 A 不用这个、模式 E 要用**：这是新手最容易困惑的点，我单独拎出来讲——

   > **关键区别：你调的函数"会不会自己切线程"**
   > - Room 的 `suspend` 函数、DataStore 的 `suspend` 函数：**内部已经切好线程了**，你调它它自己会去后台跑，所以模式 A 不用 `withContext`。
   > - 你自己写的 OkHttp 同步调用（`execute()`）、或者任何纯 CPU 计算：**不会自动切**，你在主线程调它就卡主线程，所以模式 E 必须 `withContext(Dispatchers.IO)` 手动切。

   一句话判断：**调别人的 `suspend` 函数通常不用管线程；调非 suspend 的阻塞函数，必须自己 `withContext`。**

3. **状态机**：先设成 `Testing`（UI 显示加载圈），请求回来再设成 `Ok` 或 `Failed`。这是"网络任务 + UI 状态反馈"的典型结构。`ChatViewModel` 的 `uploadFile`（`:229`）和 `loadSessionContext`（`:489`）也是这个套路。

### 不这样写会怎样

- 不加 `withContext(IO)` 直接调 `ping()`：主线程被网络请求卡住，5 秒超时期间屏幕完全冻住，弹 ANR。
- 把整块都丢到后台线程、不切回主线程：在后台线程更新 `_connectionState.value` 可能出问题（StateFlow 更新理论上线程安全，但其他 UI 操作不安全）。

**一句话**：凡是调"不会自己切线程的阻塞操作"，就得 `withContext(Dispatchers.IO)` 手动切，干完自动回主线程。

---

# 第三部分：一个统一的心智模型

把这 5 种模式串起来，其实就是回答两个问题：

**问题 1：这个任务是短暂的，还是长期的？**

- 短暂的写操作 → 模式 A（派出去不管）
- 长期的监听 → 模式 B（`init` 里 `collect`）
- 长期且要能取消 → 模式 C（存 Job）

**问题 2：里面调的东西会不会卡主线程？**

- 调 Room/DataStore 的 `suspend` → 不用管，自动切
- 调自己的阻塞代码（OkHttp 同步、CPU 计算）→ 必须 `withContext(IO)`，即模式 E

而模式 D 是 A/C/E 的组合：顺序准备 + 可取消长流。

记住这两条判断，遇到新代码你就能自己推断"这里为什么这么写"了。

---

# 第四部分：聊天 UI 功能迭代记录

> 每条记录格式：选型依据 / 实现原理 / 困难（有则写）。

## 功能 1：移除消息气泡双方头像

### 选型依据

用户要求取消用户与助手两侧的头像展示，让对话区更聚焦于文本内容。
头像移除后，原先「指向头像一侧」的不对称尖角（`bottomStart`/`bottomEnd` 取 5dp 的那一侧）失去了参照物，保留它只会显得像随机缺角，因此把气泡四角统一为对称圆角。

### 实现原理

`MessageBubble` 用一个 `Row` + `horizontalArrangement`（user=`End`，assistant=`Start`）控制气泡的左右对齐，头像作为 `Row` 的兄弟子项放在气泡外侧（assistant 在左、user 在右）。

- 删除两段头像分支（`if (!isUser) { BrandMark(); Spacer() }` 与 `if (isUser) { Spacer(); UserAvatar() }`）后，`Row` 只剩一个 `Column` 子项，`Arrangement.End/Start` 仍负责把气泡推到右/左，左右对齐逻辑不受影响。
- `BrandMark` / `UserAvatar` 定义在 `chat.components` 包内，与 `MessageBubble` 同包，原本就无需 import，删掉调用即可，不存在悬空 import。
- `BrandMark` 仍被 `ChatScreen` 的 TopAppBar、会话列表、设置页使用，所以**不删除** `Avatars.kt`，只改 `MessageBubble`。`UserAvatar` 此后无调用方，但保留定义不碍事，避免连带改动。
- 气泡 `Surface` 的 `shape` 从分段 `RoundedCornerShape(topStart=18, …, bottomStart=if(isUser)18 else 5, …)` 简化为 `RoundedCornerShape(18.dp)`。
- 顺手去掉 `Row` 上已无意义的 `verticalAlignment = Alignment.Bottom`（那是为了让气泡底部与头像底部对齐）。

### 困难

无。

